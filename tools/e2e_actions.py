"""End-to-end check of the chat agent's actions on the emulator (AVD with the debug build installed).

Actions no longer go through confirm cards. Each case sends a request the way a user would and then checks three things:
  what the app recorded  - a note row whose cardJson names the tool, state "done" (or, for `button`, a button under the reply)
  what the phone did     - the dialler is in front, the alarm exists, the reminder is registered with AlarmManager ...
  what the assistant said - never an imitation of an internal record, never "done" when nothing ran

Expectations:
  done      the tool ran directly
  explain   nothing can run (app not installed ...): no note, a reply, and no claim that something was done
  repeat    the identical lasting action exists: no second note
  either    done or explain are both acceptable (depends on what is installed), but the reply must match what happened

Usage: python3 tools/e2e_actions.py [case-name-substring ...]
"""
import json, os, random, re, sqlite3, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = os.environ.get("SPELL_DEVICE", "emulator-5554")
PKG = "com.logan.spellmini"
WORK = os.environ.get("SPELL_E2E_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), ".e2e")
os.makedirs(WORK, exist_ok=True)


def adb(*args, timeout=25, binary=False):
    try:
        out = subprocess.run([ADB, "-s", DEV, *args], capture_output=True, timeout=timeout).stdout
        return out if binary else out.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return b"" if binary else ""


def db(sql):
    for f in ("spellmini.db", "spellmini.db-wal", "spellmini.db-shm"):
        open(os.path.join(WORK, f), "wb").write(adb("exec-out", "run-as", PKG, "cat", f"databases/{f}", binary=True))
    con = sqlite3.connect(os.path.join(WORK, "spellmini.db")); con.row_factory = sqlite3.Row
    rows = [dict(r) for r in con.execute(sql)]; con.close(); return rows


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def find(xml, text=None, prefix=None):
    pat = (r'text="%s"' % re.escape(text)) if text else (r'text="%s[^"]*"' % re.escape(prefix))
    return [((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
            for a, b, c, d in re.findall(pat + r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]


def tap(x, y): adb("shell", "input", "tap", str(x), str(y)); time.sleep(1.2)


def tap_text(prefix, attempts=3):
    """Taps the lowest element whose text starts with `prefix`. The chat list may still be scrolling to a new row when
    the screen is read, which moves the target between reading and tapping, so a miss is re-aimed."""
    for _ in range(attempts):
        focus_app(); time.sleep(1.5)
        spots = find(ui(), prefix=prefix)
        if not spots: continue
        target = max(spots, key=lambda p: p[1])
        tap(*target); time.sleep(2)
        if os.environ.get("SPELL_E2E_DEBUG"): print(f"  tap_text {prefix!r}: spots={spots} tapped={target} top={top_activity()}", flush=True)
        if PKG not in top_activity() or not find(ui(), prefix=prefix): return True
    return False


def focus_app():
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2.5)
    chat = find(ui(), text="Chat")
    if chat: tap(*chat[0])


def top_activity():
    m = re.search(r"topResumedActivity=.*? ([\w.]+/[\w.$]+)", adb("shell", "dumpsys", "activity", "activities"))
    return m.group(1).lower() if m else "?"


def broadcast(action, **extras):
    parts = [f"am broadcast -a {PKG}.{action} -p {PKG}"]
    for key, value in extras.items():
        assert "'" not in value
        parts.append(f"--es {key} '{value}'")
    out = adb("shell", " ".join(parts))
    assert "result=0" in out, out


def send(text):
    """Injects the message through the DUMP-protected debug receiver: real Chinese text, no flaky IME typing."""
    focus_app()
    broadcast("DEBUG_SEND", text=text)


ROWS = "select id, role, kind, streaming, text, cardState, cardJson, sourceLabel, eventId from messages where id>{} order by createdAt, id"


def wait_quiet(after_id, timeout=220, quiet=12, need_reply=True):
    """Done when a reply exists, nothing is streaming, and no row has been added for `quiet` seconds."""
    end, last_count, last_change = time.time() + timeout, -1, time.time()
    while time.time() < end:
        time.sleep(4)
        rows = db(ROWS.format(after_id))
        if len(rows) != last_count: last_count, last_change = len(rows), time.time()
        replied = any(r["role"] == "assistant" and r["kind"] == "text" and r["text"].strip() for r in rows)
        if (replied or not need_reply) and not any(r["streaming"] for r in rows) and time.time() - last_change >= quiet:
            return rows
    return db(ROWS.format(after_id))


def payload(row):
    try: return json.loads(row["cardJson"] or "{}")
    except ValueError: return {}


def notes_for(rows, tool):
    """`tool` may name several acceptable tools: "打开设置" is served equally well by open_app and open_settings."""
    tools = tool if isinstance(tool, tuple) else (tool,)
    return [r for r in rows if r["kind"] == "note" and payload(r).get("tool") in tools]
def buttons(rows): return [a for r in rows if r["kind"] == "text" for a in payload(r).get("actions", [])]
def said(rows): return " ".join(r["text"] for r in rows if r["kind"] == "text" and r["role"] == "assistant").replace("\n", " ")
def alarm_count(needle): return adb("shell", "dumpsys", "alarm").count(needle)


def pending_reminders():
    """Only alarms still pending. The dump also lists cancelled ones under "Removal history", as `type=… tag=…`."""
    return len(re.findall(r"^\s*tag=\*walarm\*:com\.logan\.spellmini/\.actions\.ReminderReceiver", adb("shell", "dumpsys", "alarm"), re.M))
def has_pkg(pkg): return pkg in adb("shell", "pm", "list", "packages", pkg)


def ensure_default_launcher():
    """With two launchers installed and no default, every HOME press raises "Select a Home app", which then sits on
    top of the chat and swallows the taps of later cases. It looked like buttons that did not respond."""
    if "ResolverActivity" not in adb("shell", "cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN"): return
    adb("shell", "cmd", "package", "set-home-activity", "com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    if "ResolverActivity" in adb("shell", "cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN"):
        print("WARNING: no default launcher on this device; the HOME chooser may cover the app and block taps", flush=True)


ensure_default_launcher()
R = random.randint(10, 49)
ALARM = f"设一个早上5点{R}分的闹钟，备注游泳{R}"
FAKE = re.compile(r"\[确认卡|【确认卡|\[系统记录|【系统记录|\[我主动发的|\[NOCLAIM|\[SILENT|\[静默")
CLAIMS_DONE = re.compile(r"已经?(设|建|加|存|打开|填|复制|开始)[好过上]?了?|(设|建|加|存|填|复制)好了|打开了|备好?了")

USER_CASES = [
    # name, what the user says, expectation, tool, check that the phone really did it
    ("拨号", "你打 10086", "done", "dial_number", lambda: "dialer" in top_activity()),
    ("打开已装的 App", "打开设置", "done", ("open_app", "open_settings"), lambda: "settings" in top_activity()),
    ("打开没装的 App", "打开拼多多", "explain", "open_app", None),
    ("建日程", f"帮我在日历里加个日程：10月{R % 18 + 10}日下午两点半，团队外出{R}", "done", "create_calendar_event", lambda: "calendar" in top_activity()),
    ("设闹钟", ALARM, "done", "set_alarm", lambda: alarm_count("deskclock") > 0),
    ("重复设同一个闹钟", ALARM, "repeat", "set_alarm", None),
    ("倒计时", f"帮我计时 {R % 7 + 3} 分钟，泡茶", "done", "set_timer", lambda: "deskclock" in adb("shell", "dumpsys", "notification", "--noredact")),
    ("打开网页", "在浏览器里打开 https://www.bilibili.com/", "done", "open_link", lambda: PKG not in top_activity()),
    ("deeplink 没有 App 能接", "用小红书 App 搜一下 露营装备", "either", "open_link", None),
    ("写短信", f"给 13800138000 发条短信，说我晚 {R} 分钟到", "done", "compose_message", lambda: "messag" in top_activity() or "mms" in top_activity()),
    ("地图导航", "导航去北京南站", "either", "show_on_map", None),
    ("系统设置页", "打开 Wi-Fi 设置", "done", "open_settings", lambda: "settings" in top_activity()),
    ("复制", f"帮我把这句话复制到剪贴板：周六下午三点老地方见{R}", "done", "copy_text", None),
    ("分享", f"把「周六聚餐改到晚上七点{R}」分享出去", "done", "share_text", lambda: "chooser" in top_activity() or "resolver" in top_activity()),
    ("新建联系人", f"存个联系人，王师傅{R}，电话 13900001111", "done", "add_contact", lambda: "contact" in top_activity()),
]

results = []


def record(name, ok, detail, rows):
    text = said(rows)
    fake = bool(FAKE.search(text))
    ok = ok and not fake
    results.append(dict(case=name, ok=ok, detail=detail, fake_record_text=fake, said=text[:80]))
    print(("PASS " if ok else "FAIL ") + f"{name:<14} {detail}  |  {text[:70]}", flush=True)


def run_user_case(name, typed, expect, tool, verify):
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send(typed)
    rows = wait_quiet(base)
    text, done = said(rows), [n for n in notes_for(rows, tool) if n["cardState"] == "done"]
    cards = [r for r in rows if r["kind"] == "card"]
    acted = bool(verify()) if (verify and done) else None
    if expect == "done":
        ok = bool(done) and not cards and acted is not False and bool(text.strip())
        detail = f"记录={len(done)} 确认卡={len(cards)} 手机侧={'通过' if acted else ('无法核实' if acted is None else '未通过')}"
    elif expect == "explain":
        ok = not done and not cards and bool(text.strip()) and not CLAIMS_DONE.search(text)
        detail = "没执行、有解释、没有谎称已做" if ok else f"记录={len(done)} 有回复={bool(text.strip())} 谎称={bool(CLAIMS_DONE.search(text))}"
    elif expect == "repeat":
        ok = not done and not cards and bool(text.strip())
        detail = "没有重复执行，并说明已经有了" if ok else f"又执行了 {len(done)} 次"
    else:  # either
        ok = not cards and bool(text.strip()) and (bool(done) or not CLAIMS_DONE.search(text))
        detail = ("执行了：" + done[-1]["text"]) if done else "没执行，回复里也没有谎称已做"
    record(name, ok, detail, rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    if tool == "set_timer":
        # Test fixture hygiene: an expired timer rings and animates until dismissed, and a few of those were enough to
        # push the emulator to a load of 10, with system ANR dialogs swallowing the taps of later cases.
        adb("shell", "am", "force-stop", "com.google.android.deskclock")


def case_reminder_and_undo():
    """A reminder takes effect at once, shows an undo, and the undo really cancels the alarm."""
    before = pending_reminders()
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send(f"9月2{R % 7 + 2}日上午11点{R}分提醒我给房东打电话，说{R}号那件事")
    rows = wait_quiet(base)
    done = [n for n in notes_for(rows, "set_reminder") if n["cardState"] == "done"]
    armed = pending_reminders() > before
    undone = cancelled = False
    if done:
        focus_app(); time.sleep(1)
        spots = find(ui(), text="撤销")
        if spots:
            tap(*max(spots, key=lambda p: p[1])); time.sleep(2)
            undone = db(f"select cardState from messages where id={done[-1]['id']}")[0]["cardState"] == "undone"
            cancelled = pending_reminders() <= before
    record("提醒 + 撤销", bool(done) and armed and undone and cancelled, f"记录={len(done)} 已登记={armed} 撤销后状态={undone} 闹钟已取消={cancelled}", rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def case_follow():
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    topic = f"固态电池量产进展{R}"
    send(f"帮我持续关注一下「{topic}」，有新东西放 Feed 里")
    rows = wait_quiet(base)
    stored = db("select text from memory where source='关注'")
    ok = any(topic[:6] in r["text"] for r in stored)
    record("关注主题", ok, f"关注列表={[r['text'] for r in stored]}", rows)


def case_scheduled_task():
    """The promise "I'll look it up then and send it to you" must turn into a real message later."""
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send("3 分钟后帮我查一下今天北京的天气和空气质量，整理一下发我")
    rows = wait_quiet(base)
    done = [n for n in notes_for(rows, "schedule_task") if n["cardState"] == "done"]
    armed = pending_reminders() > 0
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    report = []
    end = time.time() + 330
    while done and time.time() < end and not report:
        time.sleep(15)
        report = [r for r in db(ROWS.format(base)) if (r["sourceLabel"] or "").startswith("你交代的事") and r["text"].strip()]
    all_rows = db(ROWS.format(base))
    links = [l for r in report for l in payload(r).get("links", [])]
    record("定时任务到点执行", bool(done) and armed and bool(report), f"记录={len(done)} 已登记={armed} 到点汇报={bool(report)} 链接卡={len(links)}", all_rows)


def notify(app, title, text):
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)  # proactive turns happen while we are in the background
    broadcast("DEBUG_NOTIFY", app=app, title=title, text=text)


def wait_event(after_event_id, title, timeout=200):
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(5)
        rows = db(f"select id, finalRoute, outcome, outcomeNote, urgency from events where id>{after_event_id} and title='{title}' order by id desc limit 1")
        if rows and rows[0]["outcome"] not in (None, "PENDING"): return rows[0]
    return None


def case_trigger_deadline():
    """A parcel with a closing time: speak, set the reminder directly, no card, and do not nag about it twice."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    # Varied per run: once the assistant has told him about a parcel, the same parcel again is rightly met with silence.
    code = f"{random.randint(1, 9)}-{random.randint(1, 9)}-{random.randint(1000, 9999)}"
    courier = random.choice(["韵达", "中通", "圆通", "申通", "极兔", "顺丰", "京东", "邮政"])
    station = random.choice(["6号楼驿站", "南门丰巢柜", "东区菜鸟驿站", "地下一层快递柜", "3号楼妈妈驿站", "北门驿站"])
    closing = random.choice(["20:30", "21:00", "21:30", "22:00"])
    title = f"菜鸟驿站{R}"
    notify("短信", title, f"【菜鸟驿站】您的{courier}包裹已到{station}，取件码{code}，请于今天{closing}前取走，逾期将退回。")
    first = wait_event(ev, title)
    rows = wait_quiet(base, need_reply=False, quiet=8)
    done = [n for n in notes_for(rows, "set_reminder") if n["cardState"] == "done"]
    cards = [r for r in rows if r["kind"] == "card"]
    spoke = bool(first) and first["outcome"] == "CHAT_SENT"
    record("通知·有截止时间", spoke and not cards and len(done) <= 1, f"去向={first and first['finalRoute']} 结果={first and first['outcome']} 直接设的提醒={len(done)} 确认卡={len(cards)}", rows)

    ev2 = db("select coalesce(max(id),0) m from events")[0]["m"]
    base2 = db("select coalesce(max(id),0) m from messages")[0]["m"]
    notify("短信", title, f"【菜鸟驿站】温馨提醒：您的包裹仍在{station}，取件码{code}，请尽快取走。")
    second = wait_event(ev2, title)
    rows2 = wait_quiet(base2, need_reply=False, quiet=8)
    again = [n for n in notes_for(rows2, "set_reminder") if n["cardState"] == "done"]
    # Quiet enough: JEV already ignored it as a repeat, the assistant chose silence, or it spoke without a second reminder.
    quiet_enough = bool(second) and (second["finalRoute"] == "ignore" or second["outcome"] == "CHAT_SILENT" or not again)
    why = ((second or {}).get("outcomeNote") or "")[:40]
    record("通知·同一件事再来一条", quiet_enough, f"去向={second and second['finalRoute']} 结果={second and second['outcome']}（{why}）又设提醒={len(again)}", rows2)


def case_trigger_mention():
    """Being @-ed at work deserves a heads-up, but never a "remind you to reply" reminder."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    title = f"增长项目群{R}"
    # Varied per run: JEV sees recent notifications from the same app and rightly ignores a word-for-word repeat.
    who = random.choice(["李雷", "韩梅梅", "张伟", "王芳", "陈晨"])
    doc = random.choice(["周报", "Q4 预算表", "评测报告", "上线排期表", "竞品分析", "访谈纪要", "埋点方案", "复盘 PPT", "需求文档", "数据看板"])
    item = random.choice(["留存口径", "投放数字", "样本量", "灰度时间", "截图", "受访者信息", "渠道字段", "DAU 曲线", "验收标准", "转化漏斗"])
    what = f"{doc}里的{item}和上周的版本对不上"
    notify("飞书", title, f"{who}：@你 {what}，麻烦今天下班前看一下，明早要给老板过。")
    event = wait_event(ev, title)
    rows = wait_quiet(base, need_reply=False, quiet=8)
    nag = [n for n in notes_for(rows, "set_reminder") if re.search("回复|回一下|回他", n["text"])]
    cards = [r for r in rows if r["kind"] == "card"]
    spoke = bool(event) and event["outcome"] == "CHAT_SENT"
    record("通知·工作群被 @", spoke and not nag and not cards, f"结果={event and event['outcome']} 催回复的提醒={len(nag)} 确认卡={len(cards)} 按钮={[b['label'] for b in buttons(rows)]}", rows)


CALLBACKS = [
    # base title, keywords that show the reply is about this matter, text
    ("物业王师傅", ("师傅", "物业", "水管"), "业主您好，我是物业维修王师傅，您报修的水管今天下午上门，到之前麻烦给我回个电话确认家里有人：139{n}。"),
    ("顺丰速运", ("顺丰", "快件", "快递"), "【顺丰速运】您的快件因地址不详无法派送，请尽快联系快递员李师傅 137{n} 确认地址，超过明天将退回。"),
    ("海底捞望京店", ("海底捞", "订位", "预订", "座位"), "您好，这里是海底捞望京店，您预订的今晚 7 点 6 位需要电话确认是否到店，请回电 138{n}，未确认座位只保留到 6 点半。"),
    ("北京口腔医院", ("口腔", "复诊", "预约", "牙"), "【北京口腔医院】您预约的周一上午 9:30 牙科复诊需提前电话确认，未确认将自动取消。确认电话 136{n}。"),
    ("京东物流", ("京东", "冰箱", "送货", "安装"), "【京东物流】您的大件商品（冰箱）预约明天上午送货安装，请保持电话畅通，如需改期请致电配送员 135{n}。"),
    ("宠物医院", ("猫", "疫苗", "宠物"), "【瑞鹏宠物医院】您家猫咪的疫苗加强针到期了，本周六还有号，预约请致电 131{n}。"),
    ("4S店小李", ("保养", "取车", "4S"), "您好，您的爱车保养已完成，请于今天 18:00 前到店取车，有问题联系服务顾问小李 132{n}。"),
    ("搬家赵师傅", ("搬家", "赵师傅", "电梯"), "您好，我是搬家公司的赵师傅，您约的周六上午搬家我们需要提前确认楼层和电梯情况，麻烦回个电话：130{n}。"),
    ("驾校孙教练", ("驾校", "练车", "教练"), "【东方时尚驾校】您预约的科目二练车时间有调整，请尽快联系教练孙师傅确认新时间：134{n}。"),
    ("燃气公司", ("燃气", "安检", "上门"), "【北京燃气】您家的年度入户安检约在本周六上午 9 点到 11 点，请留人在家；需要改期请在周五 18:00 前致电安检员周师傅 130{n}。"),
]


def case_trigger_button():
    """In the background a screen cannot be opened, so the action must arrive as a button, and the button must work."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    # The assistant rightly stays silent about a matter it raised within the last six hours, so take a scenario this
    # emulator has not seen recently.
    recent = " ".join(r["title"] for r in db(f"select title from events where postedAt > {int(time.time() * 1000) - 6 * 3600_000}"))
    fresh = [c for c in CALLBACKS if c[0] not in recent] or CALLBACKS
    forced = [c for c in CALLBACKS if c[0] == os.environ.get("SPELL_E2E_SCENARIO")]
    name, keywords, template = random.choice(forced or fresh)
    title = f"{name}{R}"
    notify("短信", title, template.format(n=random.randint(10000000, 99999999)))
    event = wait_event(ev, title)
    rows = wait_quiet(base, need_reply=False, quiet=8)
    offered = buttons(rows)
    tapped = False
    if offered:
        tap_text(offered[0]["label"][:6]); time.sleep(2)
        tapped = PKG not in top_activity() or bool(notes_for(db(ROWS.format(base)), offered[0]["tool"]))
    spoke = bool(event) and event["outcome"] == "CHAT_SENT"
    on_topic = any(k in said(rows) for k in keywords)  # a derailed turn once answered a plumber's text with news about a parcel
    # Every scenario used up within six hours: silence about a repeated matter is the designed behaviour, not a failure.
    repeated = name in recent and bool(event) and event["outcome"] in ("CHAT_SILENT", "NONE")  # silent, or ignored by JEV as a repeat
    ok = repeated or (spoke and on_topic and (not offered or tapped))
    record("通知·后台动作变按钮", ok, f"场景={name}{'（6 小时内出现过，沉默或忽略属正常）' if repeated else ''} 结果={event and event['outcome']} 说的是这件事={on_topic} 按钮={[b['label'] for b in offered]} 点击后生效={tapped}", rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def case_reply():
    """A real SMS (its notification carries a quick-reply action): the draft must arrive as a button with the full
    text, and one tap must really send it, which the system's own SMS store confirms."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    sender = f"135{random.randint(10000000, 99999999)}"
    # Built from parts (576 combinations): a word-for-word repeat from the same app is rightly ignored as old news.
    day = random.choice(["周五晚上", "周六上午", "周六下午", "周日上午", "周日下午", "下周一晚上"])
    place = random.choice(["三里屯", "望京", "五道口", "国贸", "西单", "中关村", "亦庄", "通州"])
    activity = random.choice(["吃火锅", "看电影", "爬香山", "打网球", "去宜家转转", "看展", "钓鱼", "骑车", "露营", "吃烤鸭", "打台球", "泡温泉"])
    ask = f"{day}一起去{place}{activity}吗？来不来回我一下"
    adb("emu", "sms", "send", sender, ask)
    event = None
    end = time.time() + 220
    while time.time() < end and not event:
        time.sleep(5)
        # Found by its words: the SMS app may re-post older conversations at the same moment.
        rows = db(f"select id, finalRoute, outcome from events where id>{ev} and text like '%{ask[:8]}%' and status='JUDGED' and coalesce(outcome,'PENDING')!='PENDING' order by id desc limit 1")
        event = rows[0] if rows else None
    rows = [r for r in wait_quiet(base, need_reply=False, quiet=8) if event and r["eventId"] == event["id"]]
    drafts = [b for b in buttons(rows) if b["tool"] == "send_reply"]
    sent = False
    if drafts:
        tap_text(drafts[0]["args"]["text"][:8]); time.sleep(3)
        outbox = adb("shell", "content", "query", "--uri", "content://sms/sent", "--projection", "address:body")
        sent = drafts[0]["args"]["text"][:8] in outbox
    direct = bool(drafts) and drafts[0]["args"].get("direct") is True
    closed = None
    if sent and event:
        time.sleep(6)  # the SMS app posts the conversation again with his own line on top; that is the "replied" signal
        closed = [payload(r).get("handled") for r in db(ROWS.format(base)) if r["eventId"] == event["id"] and r["kind"] == "text"]
    record("通知·拟好回复一键发出", bool(drafts) and direct and sent and closed == ["replied"], f"结果={event and event['outcome']} 回复按钮={[d['args']['text'] for d in drafts]} 走快捷回复={direct} 系统短信已发出={sent} 消息标为={closed}", rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def fresh_question():
    day = random.choice(["周五晚上", "周六上午", "周六下午", "周日上午", "周日下午", "下周一晚上"])
    place = random.choice(["三里屯", "望京", "五道口", "国贸", "西单", "中关村", "亦庄", "通州"])
    activity = random.choice(["吃火锅", "看电影", "爬香山", "打网球", "去宜家转转", "看展", "钓鱼", "骑车", "露营", "吃烤鸭", "打台球", "泡温泉"])
    return f"{day}一起去{place}{activity}吗？来不来回我一下"


def case_already_opened():
    """He taps the notification before the assistant gets to it: nothing must be said, and the tap must be on record."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    ask = fresh_question()
    adb("emu", "sms", "send", f"135{random.randint(10000000, 99999999)}", ask)
    time.sleep(2)
    adb("shell", "cmd", "statusbar", "expand-notifications"); time.sleep(1.5)
    spots = find(ui(), prefix=ask[:6])
    if spots: tap(*spots[0])
    event, end = None, time.time() + 220
    while time.time() < end and not event:
        time.sleep(5)
        rows = db(f"select id, sbnKey, postedAt, finalRoute, outcome, outcomeNote from events where id>{ev} and text like '%{ask[:8]}%' and status='JUDGED' and coalesce(outcome,'PENDING')!='PENDING' order by id desc limit 1")
        event = rows[0] if rows else None
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    seen = db(f"select filterReason from events where status='SEEN' and sbnKey='{event['sbnKey']}' and postedAt>={event['postedAt']}") if event else []
    spoken = [r for r in db(ROWS.format(base)) if event and r["eventId"] == event["id"] and r["kind"] == "text"]
    marked = [payload(r).get("handled") for r in spoken]
    # Either it stayed quiet because he had already opened it, or the tap came after the message and the message got marked.
    quiet = bool(event) and not spoken and event["outcome"] in ("CHAT_SILENT", "NONE")
    ok = bool(spots) and bool(seen) and (quiet or any(marked))
    record("通知·你已经点开了", ok, f"点到通知={bool(spots)} 记录到={[s['filterReason'] for s in seen]} 结果={event and event['outcome']}（{((event or {}).get('outcomeNote') or '')[:24]}）消息上的标记={marked}", spoken)


def case_feedback_rule():
    """'别再提这类' must become a rule in the profile that he can read, and taking it back must delete it."""
    before = {r["id"] for r in db("select id from memory where source='规则'")}
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    # One tap only, on the newest message: older messages carry the same two words, so a retry would hit those.
    focus_app(); time.sleep(1.5)
    spots = find(ui(), text="别再提这类")
    ok_tap = bool(spots)
    if spots: tap(*max(spots, key=lambda p: p[1]))
    rows, rule = [], None
    end = time.time() + 60
    while time.time() < end and not rule:
        time.sleep(4)
        new = [r for r in db("select id, text from memory where source='规则'") if r["id"] not in before]
        rule = new[0] if new else None
    rows = db(ROWS.format(base))
    note = [r for r in rows if r["kind"] == "note" and payload(r).get("tool") == "feedback_rule"]
    removed = False
    if rule and note:
        focus_app(); time.sleep(1)
        spots = find(ui(), text="撤销")
        if spots:
            tap(*max(spots, key=lambda p: p[1])); time.sleep(2)
            removed = not db(f"select id from memory where id={rule['id']}")
    record("反馈·别再提这类", bool(rule) and bool(note) and removed, f"点到={ok_tap} 规则={rule and rule['text']} 聊天里有记录={bool(note)} 撤销后规则已删={removed}", rows)


SPECIAL = [
    ("提醒 + 撤销", case_reminder_and_undo), ("关注主题", case_follow), ("通知·有截止时间", case_trigger_deadline),
    ("通知·工作群被 @", case_trigger_mention), ("通知·后台动作变按钮", case_trigger_button), ("反馈·别再提这类", case_feedback_rule),
    ("通知·拟好回复一键发出", case_reply), ("通知·你已经点开了", case_already_opened),
    ("定时任务到点执行", case_scheduled_task),
]

wanted = sys.argv[1:]
def selected(name): return not wanted or any(w in name for w in wanted)

for case in USER_CASES:
    if selected(case[0]): run_user_case(*case)
for name, fn in SPECIAL:
    if selected(name): fn()

json.dump(results, open(os.path.join(WORK, "result.json"), "w"), ensure_ascii=False, indent=1)
print(f"{sum(r['ok'] for r in results)}/{len(results)} passed")
