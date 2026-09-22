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
    # A long run outlasts the screen timeout; a dark screen reads as "nothing on screen" and every tap misses.
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2.5)
    # The app may have been left inside the hub or a finished page (by an earlier case, or by hand): back out to the two tabs.
    for _ in range(3):
        chat = find(ui(), text="Chat")
        if chat: break
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
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
adb("shell", "svc", "power", "stayon", "true")  # test device only: keep the screen on for the length of the run
R = random.randint(10, 49)
# Always a couple of hours ahead: run at dawn, "早上 5 点" was already past, and the model reasoned about a missed alarm instead.
ALARM = f"设一个{(time.localtime().tm_hour + 2) % 24}点{R}分的闹钟，备注游泳{R}"
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
    if not ok:  # what was on the screen when it failed says more than the database does
        shot = adb("exec-out", "screencap", "-p", binary=True)
        if shot: open(os.path.join(WORK, f"fail-{len(results)}.png"), "wb").write(shot)
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
        # "没装，我用浏览器开了网页版" is an honest answer too: a claim is fine when some tool really ran in this turn.
        other = [r for r in rows if r["kind"] == "note" and r["cardState"] == "done" and payload(r).get("tool")]
        ok = not done and not cards and bool(text.strip()) and (not CLAIMS_DONE.search(text) or bool(other))
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
        rows = db(f"select id, finalRoute, outcome, outcomeNote, urgency, jevSource from events where id>{after_event_id} and title='{title}' order by id desc limit 1")
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
    who = random.choice(["李雷", "韩梅梅", "张伟", "王芳", "陈晨"])
    # Work situations that differ in kind, not just in wording: after a day of near-identical "数据对不上" mentions the
    # assistant rightly said "同类的事今天提过了" and kept quiet.
    group, ask = random.choice([
        ("发布评审群", "明天上线的回滚方案还差你确认，今天下班前回一下，不然发布要顺延"),
        ("客户成功群", "A 客户下午三点临时要加一场演示，点名要你讲评测那部分，能不能来说一声"),
        ("行政通知群", "你的工位下周一搬到 12 层，周五下班前把个人物品装箱贴好标签"),
        ("招聘面试群", "周四下午两点的候选人面试官换成你了，简历我发你邮箱，麻烦确认时间"),
        ("财务报销群", "你上个月的差旅报销单缺两张发票，本周三前不补就要退单重提"),
        ("法务合规群", "新版数据合规承诺书需要本人签署，周五截止，链接在群公告里"),
    ])
    title = f"{group}{R}"
    notify("飞书", title, f"{who}：@你 {ask}")
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
    # Open it from the shade and make sure the SMS app really came up: a tap on the drop-down banner sometimes only
    # expands it, and then nothing was "opened" at all. (With dozens of unread test conversations the SMS app folds them
    # into one group and the new line cannot be found; reset the SMS app's data on the test device when that happens.)
    spots = []
    for _ in range(3):
        time.sleep(2)
        adb("shell", "cmd", "statusbar", "expand-notifications"); time.sleep(1.5)
        spots = find(ui(), prefix=ask[:6])
        if not spots: continue
        tap(*spots[0]); time.sleep(2)
        if "messag" in top_activity(): break
    opened = "messag" in top_activity()
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
    ok = opened and bool(seen) and (quiet or any(marked))
    record("通知·你已经点开了", ok, f"点开了短信 App={opened} 记录到={[s['filterReason'] for s in seen]} 结果={event and event['outcome']}（{((event or {}).get('outcomeNote') or '')[:24]}）消息上的标记={marked}", spoken)


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


def tasks_in_store():
    rows = db("select id, text from memory where source='任务'")
    out = []
    for r in rows:
        try: out.append(dict(json.loads(r["text"]), id=r["id"]))
        except ValueError: pass
    return out


def case_lookup_only():
    """A question is a question: looking something up must not dial, navigate or open anything on its own."""
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send("翻一下通知，最近谁给我发过带电话号码的短信？只告诉我就行")
    rows = wait_quiet(base)
    opened = [r for r in rows if r["kind"] == "note" and payload(r).get("tool") in ("dial_number", "show_on_map", "open_link", "open_app", "compose_message", "create_calendar_event")]
    record("只问不做", bool(said(rows).strip()) and not opened, f"自作主张的动作={[payload(r).get('tool') for r in opened]}", rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def case_recurring_task():
    """A repeating task lands in 在办 with the right rule, and the note in the chat can take it back."""
    before = {t["id"] for t in tasks_in_store()}
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send(f"每周五下午 5 点{R % 5 + 1}0 分给我整理一份本周 AI Agent 方向的进展")
    rows = wait_quiet(base)
    new = [t for t in tasks_in_store() if t["id"] not in before]
    right = bool(new) and new[0]["kind"] == "recurring" and new[0]["repeat"] == "weekly" and new[0]["weekday"] == 5 and new[0]["at"].startswith("17:")
    armed = "task_id" in adb("shell", "dumpsys", "alarm") or pending_reminders() > 0
    undone = False
    if new:
        focus_app(); time.sleep(1)
        spots = find(ui(), text="撤销")
        if spots:
            tap(*max(spots, key=lambda p: p[1])); time.sleep(2)
            undone = new[0]["id"] not in {t["id"] for t in tasks_in_store()}
    record("定期任务 + 撤销", right and undone, f"任务={[(t['title'], t['repeat'], t.get('weekday'), t['at']) for t in new]} 撤销后已删={undone}", rows)


def case_watch_feed():
    """A watch on a real feed: created with the feed attached, and a run reports what the feed really holds."""
    before = {t["id"] for t in tasks_in_store()}
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    repo = random.choice(["pytorch/pytorch", "vllm-project/vllm", "langchain-ai/langchain", "huggingface/transformers", "ollama/ollama"])
    send(f"帮我盯着 GitHub 上 {repo} 的新版本，有了告诉我，每 3 小时看一次")
    rows = wait_quiet(base)
    new = [t for t in tasks_in_store() if t["id"] not in before]
    has_feed = bool(new) and new[0]["kind"] == "watch" and "releases.atom" in new[0].get("feed", "")
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    ran = None
    if new:
        send("把刚加的这个盯着的事现在跑一次")
        end = time.time() + 150
        while time.time() < end and not ran:
            time.sleep(6)
            got = db(f"select outcome, outcomeNote from events where id>{ev} and status='TASK' order by id desc limit 1")
            ran = got[0] if got else None
    record("盯着·订阅源", has_feed and bool(ran) and ran["outcome"] in ("CHAT_SENT", "CHAT_SILENT"), f"任务={[(t['title'], t.get('feed', '')[:50]) for t in new]} 跑一次={ran and ran['outcome']}（{((ran or {}).get('outcomeNote') or '')[:50]}）", db(ROWS.format(base)))
    for t in new: send(f"把在办里的 #{t['id']} 删掉"); wait_quiet(db("select coalesce(max(id),0) m from messages")[0]["m"] - 1, timeout=60)


def case_job():
    """A piece of work comes back as a page: Markdown with sections and sources, stored as a card, handed over in the chat."""
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    topic = random.choice(["北京周边适合带老人的两天一夜温泉行程", "三款两千元以内的降噪耳机对比，通勤地铁用", "给新手的手冲咖啡入门装备清单，预算一千五", "国庆去青岛三天两晚的行程，不想太赶"])
    send(f"帮我做一份：{topic}")
    end, done = time.time() + 420, None
    while time.time() < end and not done:
        time.sleep(10)
        got = db(f"select outcome, outcomeNote, outcomeRefId, downstreamCostUsd from events where id>{ev} and status='TASK' and title like '成品%' order by id desc limit 1")
        done = got[0] if got else None
    rows = db(ROWS.format(base))
    doc = db(f"select title, length(body) n, body, sourcesJson from feed where id={done['outcomeRefId']}")[0] if done and done["outcomeRefId"] else None
    handed = [r for r in rows if payload(r).get("docId")]
    good = bool(doc) and doc["n"] > 600 and "#" in doc["body"] and len(json.loads(doc["sourcesJson"])) >= 2
    record("交办一件活·成品", bool(done) and done["outcome"] == "CHAT_SENT" and good and bool(handed), f"成品={doc and doc['title']} 字数={doc and doc['n']} 来源={doc and len(json.loads(doc['sourcesJson']))} 花费=${(done or {}).get('downstreamCostUsd') or 0:.3f}（{((done or {}).get('outcomeNote') or '')[-24:]}）", rows)


def case_share_text():
    """Text shared from another app arrives next to the composer, and a quick ask gets an answer about it."""
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    note = f"周{random.choice('一二三四五')}下午三点在{random.choice(['望京 SOHO', '中关村软件园', '国贸三期'])}和{random.choice(['李总', '王经理', '陈老师'])}过方案，带上打印好的评测报告两份，提前十分钟到"
    adb("shell", f"am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '{note}' -n {PKG}/.share.ShareActivity")
    time.sleep(4)
    shown = bool(find(ui(), prefix="分享来的"))
    spots = find(ui(), text="总结一下")
    if spots: tap(*spots[0])
    rows = wait_quiet(base, timeout=120)
    record("分享进来的文字", shown and bool(spots) and bool(said(rows).strip()), f"出现分享条={shown} 点到快捷问法={bool(spots)}", rows)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def case_open_loop():
    """A promise with a time in a notification becomes something the assistant waits on."""
    before = {t["id"] for t in tasks_in_store()}
    who = random.choice(["供应商老陈", "装修队刘工", "律所张律师", "猎头 Amy", "房东孙阿姨"])
    thing = random.choice(["报价单", "施工排期", "合同修改稿", "候选人名单", "续租合同"])
    day = random.choice(["周三上午十点前", "周四下班前", "后天中午前", "周五下午三点前"])
    title = f"{who}{R}"
    notify("微信", title, f"不好意思久等，{thing}我这边还在核，{day}一定发您邮箱。")
    wait_event(db("select coalesce(max(id),0) m from events")[0]["m"] - 1, title, timeout=120)
    time.sleep(3)
    broadcast("DEBUG_LOOPS")
    new, end = [], time.time() + 90
    while time.time() < end and not new:
        time.sleep(6)
        new = [t for t in tasks_in_store() if t["id"] not in before and t["kind"] == "loop"]
    mine = [t for t in new if thing[:2] in t["title"] + t["instruction"] or who[:3] in t["title"] + t["instruction"] + t.get("about", "")]
    record("该来没来·记下等下文的事", bool(mine) and mine[0]["nextAt"] > time.time() * 1000, f"记下={[(t['title'], t['instruction'][:30]) for t in new]}", [])


# ---------------------------------------------------------------- subscribed sources

def sources_in_store():
    out = []
    for r in db("select id, text from memory where source='订阅'"):
        try: out.append({"id": r["id"], **json.loads(r["text"])})
        except ValueError: pass
    return out


def style_ok(card):
    """Every card is written to one spec (CardStyle): short title, a body of at most two sentences, up to three bullets of hard facts, no links in bullets."""
    bullets = json.loads(card["bulletsJson"] or "[]")
    return (len(card["title"]) <= 32 and len(card["body"]) <= 84 and card["body"].count("。") <= 2 and len(bullets) <= 3
            and all(len(b) <= 28 and "http" not in b for b in bullets))


def item(source, title, text, link):
    """An item of a subscribed source, through the same entry the poller uses."""
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    broadcast("DEBUG_ITEM", source=source, title=title, text=text, link=link)


def case_sources_seeded():
    """A fresh install, and an upgrade, both end up with the presets; the noisy ones start off."""
    found = sources_in_store()
    on = [s["name"] for s in found if s.get("enabled")]
    off = [s["name"] for s in found if not s.get("enabled")]
    record("订阅·预置源", len(found) >= 6 and any("AIHOT" in n for n in on) and any("arXiv" in n for n in off), f"开着={on} 关着={off}", [])


def case_item_routes():
    """Three items, three fates: of interest -> a card that links to the item; off-profile -> ignored; the same news reworded -> no second card."""
    n = random.randint(100, 999)
    maker, bench = random.choice([("智谱", "AgentBench"), ("月之暗面", "τ-bench"), ("阶跃星辰", "BFCL"), ("面壁智能", "ToolSandbox")])
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    good_title = f"{maker}开源手机端 Agent 评测集 M{n}，覆盖 {n} 个真实 App 任务"
    good_link = f"https://example.com/agent-eval-{n}"
    item("测试源", good_title, f"{maker}发布面向手机端 Agent 的评测集 M{n}，包含 {n} 个跨 App 真实任务，给出了任务完成率与步数两个指标，并在 {bench} 上对比了主流模型的工具调用稳定性。数据与评测脚本已开源。", good_link)
    good = wait_event(ev, good_title)
    card = db(f"select title, body, bulletsJson, sourceLabel, sourcesJson from feed where eventId=(select id from events where title='{good_title}' order by id desc limit 1)")
    linked = bool(card) and good_link in card[0]["sourcesJson"] and card[0]["sourceLabel"].startswith("订阅 · ")
    styled = bool(card) and style_ok(card[0]) and "复核" in (good or {}).get("outcomeNote", "")

    junk_title = f"某交易所上线 {n} 倍杠杆新币，注册即送空投"
    item("测试源", junk_title, "限时活动：注册并完成首笔交易即可领取空投奖励，邀请好友再得返佣，名额有限先到先得。", f"https://example.com/promo-{n}")
    junk = wait_event(ev, junk_title)

    again_title = f"手机端 Agent 新评测集 M{n} 开源：{n} 个 App 任务，{maker}出品"
    item("另一个测试源", again_title, f"{maker}今天开源了手机端 Agent 评测集 M{n}，共 {n} 个真实 App 任务，指标是完成率和步数，并对比了主流模型的工具调用表现。", f"https://example.org/news/{n}")
    again = wait_event(ev, again_title)
    second = db(f"select count(*) c from feed where eventId=(select id from events where title='{again_title}' order by id desc limit 1)")[0]["c"]

    ok = bool(good) and good["outcome"] == "FEED_CARD" and linked and styled and bool(junk) and junk["finalRoute"] == "ignore" and bool(again) and second == 0
    record("订阅·分流：成卡 / 忽略 / 重复不出第二张", ok,
           f"相关={good and (good['finalRoute'], good['outcome'])} 带原文链接={linked} 样式合规={styled} 无关={junk and junk['finalRoute']} "
           f"重复={again and (again['finalRoute'], again['outcome'], (again['outcomeNote'] or '')[:40])} 第二张卡={second}", [])


def case_item_touches_work():
    """An item that changes something he is working on may become a message; silence still ends as a card, never as nothing."""
    n = random.randint(3, 9)
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    # The profile says he is choosing between these three on price and tool-call stability. The subject changes from run
    # to run: told about one of them an hour ago, the assistant rightly stays silent about the same story again.
    vendor, model = random.choice([("DeepSeek", f"V{n}.{random.randint(1, 9)}"), ("Anthropic", f"Claude Haiku {n}.{random.randint(1, 9)}"), ("OpenAI", f"GPT-{n}.{random.randint(1, 9)} mini")])
    change, detail = random.choice([
        (f"API 价格下调 {random.choice([40, 50, 60])}%", "输入输出价格同步下调，新价格即日生效，老版本价格不变。"),
        ("工具调用改为严格模式，旧的调用格式下月停用", "多轮工具调用必须使用新的严格 schema，旧格式 30 天后返回错误，官方给出了迁移说明。"),
        (f"上下文扩到 {random.choice([1, 2, 4])}M 且不加价", "长上下文不再单独计费，官方称多轮工具调用的成功率明显提高，并已上架 OpenRouter。"),
    ])
    title = f"{vendor} 发布 {model}：{change}"
    link = f"https://example.com/{vendor.lower()}-{n}-{random.randint(1000, 9999)}"
    item("测试源", title, f"{vendor} 今天发布 {model}。{detail}适合对成本敏感的手机端 Agent 场景。", link)
    got = wait_event(ev, title, timeout=260)
    rows = db(ROWS.format(base))
    links = [l.get("url") for r in rows for l in payload(r).get("links", [])]
    spoke = bool(got) and got["outcome"] == "CHAT_SENT"
    carded = bool(got) and got["outcome"] == "FEED_CARD"
    no_button = not any(r["kind"] == "text" and "查看原消息" in (r["text"] or "") for r in rows)
    ok = (spoke and link in links) or carded
    record("订阅·碰到手头的事", ok and no_button, f"结果={got and (got['finalRoute'], got['outcome'])} 消息带原文链接={link in links} 说了={said(rows)[:70]}", rows)


def case_subscribe_in_chat():
    """"订阅……" in chat adds a source after reading it once, leaves an undoable note, and the first look happens right away."""
    before = {s["id"] for s in sources_in_store()}
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    repo = random.choice(["vllm-project/vllm", "ollama/ollama", "huggingface/transformers", "langchain-ai/langchain"])
    send(f"订阅 GitHub 上 {repo} 的新版本发布，放进我的订阅源里")
    rows = wait_quiet(base)
    new = [s for s in sources_in_store() if s["id"] not in before]
    noted = notes_for(rows, "source")
    time.sleep(25)
    polled = [s for s in sources_in_store() if s["id"] in {x["id"] for x in new} and s.get("lastPolledAt", 0) > 0 and not s.get("lastError")]
    ok = bool(new) and "releases.atom" in new[0]["url"] and bool(noted) and bool(polled)
    record("订阅·聊天里订阅", ok, f"新源={[(s['name'], s['url'][:60]) for s in new]} 记录={len(noted)} 已看第一眼={bool(polled)}（交给分流 {polled and polled[0].get('taken')} 条）", rows)
    for s in new: send(f"取消订阅 #{s['id']}"); wait_quiet(db("select coalesce(max(id),0) m from messages")[0]["m"] - 1, timeout=60)


def case_poll_live():
    """The real presets answer, and a first look takes a taste rather than the backlog."""
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    broadcast("DEBUG_POLL")
    end = time.time() + 150
    while time.time() < end:
        time.sleep(8)
        live = [s for s in sources_in_store() if s.get("enabled")]
        if live and all(s.get("lastPolledAt", 0) > 0 for s in live): break
    live = [s for s in sources_in_store() if s.get("enabled")]
    failed = [(s["name"], s.get("lastError", "")[:40]) for s in live if s.get("lastError")]
    taken = db(f"select pkg, count(*) c from events where id>{ev} and pkg like 'feed.%' and pkg != 'feed.debug' group by pkg")
    flood = [t for t in taken if t["c"] > 40]
    record("订阅·真实源能读", bool(live) and len(failed) <= 1 and not flood, f"读了 {len(live)} 个，失败={failed} 各源交给分流={[(t['pkg'], t['c']) for t in taken]}", [])


# ---------------------------------------------------------------- 0.10: context, moments, outside sources of every kind

def grant_for_signals():
    """The test phone grants what a user would grant from the signals page; on a real phone that is the user's to do."""
    for p in ("READ_CALENDAR", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_MEDIA_IMAGES", "POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
    adb("shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")
    broadcast_int("DEBUG_CAP", 80)  # a test hour holds more proactive messages than a day of real use
    # ...and for the same reason "messaged five times this hour" would hold every message of a run for the next break.
    signal("fatigue", False)


def broadcast_int(action, value):
    assert "result=0" in adb("shell", f"am broadcast -a {PKG}.{action} -p {PKG} --ei value {value}")


def signal(sid, on=True):
    assert "result=0" in adb("shell", f"am broadcast -a {PKG}.DEBUG_SIGNAL -p {PKG} --es id {sid} --ez on {'true' if on else 'false'}")


def forget(sid):
    """Moments are paced (a cooldown, so many a day); a test must not inherit the pacing of the run before it."""
    assert "result=0" in adb("shell", f"am broadcast -a {PKG}.DEBUG_FORGET -p {PKG} --es id {sid}"); time.sleep(1)


def test_calendar():
    """A local calendar on the test phone, created once."""
    found = adb("shell", "content query --uri content://com.android.calendar/calendars --projection _id:account_name")
    m = re.search(r"_id=(\d+), account_name=spelltest", found)
    if m: return int(m.group(1))
    adb("shell", "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=spelltest&account_type=LOCAL' "
        "--bind account_name:s:spelltest --bind account_type:s:LOCAL --bind name:s:spelltest --bind calendar_displayName:s:spelltest "
        "--bind calendar_color:i:255 --bind calendar_access_level:i:700 --bind ownerAccount:s:spelltest --bind visible:i:1 --bind sync_events:i:1")
    return test_calendar()


def add_event(title, start_in_s, length_s=3600):
    now = int(adb("shell", "date", "+%s").strip())
    adb("shell", f"content insert --uri content://com.android.calendar/events --bind calendar_id:i:{test_calendar()} --bind title:s:{title} "
        f"--bind dtstart:l:{(now + start_in_s) * 1000} --bind dtend:l:{(now + start_in_s + length_s) * 1000} --bind eventTimezone:s:Asia/Shanghai")


def clear_events():
    adb("shell", f"content delete --uri content://com.android.calendar/events --where 'calendar_id={test_calendar()}'")


def moment_rows(after_id, sid):
    return db(f"select id, title, text, finalRoute, routeProbs, jevSource, outcome, outcomeNote from events where id>{after_id} and pkg='signal.{sid}' order by id")


def wait_moment(after_id, sid, timeout=200):
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(6)
        rows = moment_rows(after_id, sid)
        if rows and rows[-1]["finalRoute"] and rows[-1]["outcome"] not in ("PENDING",) and (rows[-1]["finalRoute"] != "chat" or rows[-1]["outcome"]): return rows
    return moment_rows(after_id, sid)


def case_context_volume():
    """In a meeting with the phone lying dark, a casual message arrives quietly and an emergency still rings: JEV reads right_now."""
    clear_events(); add_event(f"季度评审会{R}", -600, 3600); time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); adb("shell", "input", "keyevent", "KEYCODE_SLEEP"); time.sleep(2)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    n = random.randint(100, 999)
    broadcast("DEBUG_NOTIFY", app="微信", title=f"老周{n}", text=random.choice(["周六晚上一起吃个饭？你定地方，不急，有空回我", "下周找个时间打球？哪天都行，你方便了告诉我"]))
    # Told about one emergency an hour ago, the assistant rightly does not repeat itself about the same one: the matter changes every run.
    who, where = random.choice(["妈", "爸", "小林", "二叔", "房东王姐"]), random.choice(["朝阳医院急诊", "小区地库", "机场高速出口", "幼儿园门口", "家里厨房"])
    emergency = random.choice([f"我在{where}把脚崴了走不了路，你现在能过来接我一下吗？看到马上回我电话", f"{where}这边水管爆了，水已经漫到走廊了，物业让业主马上到场，你能立刻过来吗",
                               f"我手机钱包都落在{where}了，现在借别人手机给你发的，你马上给这个号回个电话", f"{where}有人把咱家车剐了，对方要走，你赶紧下来一趟，带上行驶证"])
    broadcast("DEBUG_NOTIFY", app="微信", title=f"{who}{n}", text=emergency)
    calm = wait_event(ev, f"老周{n}"); loud = wait_event(ev, f"{who}{n}")
    rows = {r["title"]: r for r in db(f"select title, jevSource, outcome, outcomeNote from events where id>{ev}")}
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); adb("shell", "input", "keyevent", "82"); clear_events()
    # Whether the casual one is worth a message at all is the route's business; what is tested here is that it never makes a sound.
    casual = rows.get(f"老周{n}", {})
    quiet = "later" in (casual.get("jevSource") or "") and (casual.get("outcome") != "CHAT_SENT" or "静默" in (casual.get("outcomeNote") or ""))
    rang = "now" in (rows.get(f"{who}{n}", {}).get("jevSource") or "") and "弹出" in (rows.get(f"{who}{n}", {}).get("outcomeNote") or "")
    record("状态·开会时不急的静默送达、要紧的照响", bool(calm) and bool(loud) and quiet and rang,
           f"闲事={rows.get(f'老周{n}', {}).get('jevSource')}（{(rows.get(f'老周{n}', {}).get('outcomeNote') or '')[:14]}） 急事={rows.get(f'{who}{n}', {}).get('jevSource')}（{(rows.get(f'{who}{n}', {}).get('outcomeNote') or '')[:8]}）", [])


def case_vibrate_not_quiet():
    """A ringer on vibrate is how many people keep their phone for years; it must not read as "asked for quiet". On one real day it
    turned all 165 interrupt verdicts into "later". Also: an urgent matter is delivered audibly even when JEV says later."""
    broadcast("DEBUG_RINGER", mode="vibrate"); clear_events()
    signal("fatigue", False)  # a test run itself sends many messages an hour; that fact is not what is tested here
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); adb("shell", "wm", "dismiss-keyguard"); adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    n = random.randint(100, 999)
    broadcast("DEBUG_NOTIFY", app="微信", title=f"小赵{n}", text=random.choice(["下周三下午两点的评审会改到三点了，会议室不变，你看方便吗？", "上次说的那份对比表我发你邮箱了，方便的时候看一眼，不急"]))
    row = wait_event(ev, f"小赵{n}")
    broadcast("DEBUG_RINGER", mode="normal")
    src = (row or {}).get("jevSource") or ""
    # With nothing occupying him the question is not even put to JEV; the trace says so, and the vibrate fact is absent.
    ok = bool(row) and "打扰：now" in src and "此刻：" in src and "quiet" not in src
    record("状态·只震动不算要清净", ok, f"打扰={src[src.find('打扰'):][:60]}", [])


def case_digest():
    """Three messages that can wait arrive during a meeting: none is delivered on its own; when the meeting is over they come as one
    briefing with the replies drafted, and each held row points at that message."""
    clear_events(); add_event(f"评审会{R}", -600, 3600); time.sleep(2)
    broadcast("DEBUG_CAP", alert="30")  # nothing short of 3.0 is urgent, so what JEV says can wait is really held
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); adb("shell", "input", "keyevent", "KEYCODE_SLEEP"); time.sleep(2)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    n = random.randint(100, 999)
    # Feishu group notifications read "<sender>：@you <text>"; asks with a date in them are what JEV reliably routes to chat,
    # and with the alert bar at 3.0 they are held all the same.
    asks = [("飞书", f"评测产研群{n}", f"小王：@你 周四下午两点的评审改到三点了，会议室不变，麻烦确认下能不能到"),
            ("飞书", f"UT 群{n}", f"老宋：@你 下周三的周会想请你讲十分钟 JEV 的用法，周一前给我个准话"),
            ("微信", f"小林{n}", f"UT 名单我更新了一版放共享文档里了，周五前你确认下有没有漏人")]
    for app, title, text in asks: broadcast("DEBUG_NOTIFY", app=app, title=title, text=text); time.sleep(3)
    rows = {}
    for _, title, _ in asks:
        r = wait_event(ev, title); rows[title] = r
    held = [t for t, r in rows.items() if r and r["outcome"] == "HELD"]
    sent_alone = [t for t, r in rows.items() if r and r["outcome"] == "CHAT_SENT"]
    ignored = [t for t, r in rows.items() if r and r["finalRoute"] == "ignore"]
    # The meeting is over: the calendar is cleared and the phone is picked up.
    clear_events(); adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); adb("shell", "wm", "dismiss-keyguard"); time.sleep(2)
    broadcast("DEBUG_BREAK", reason=f"开完评审会{R}"); broadcast("DEBUG_CAP", alert="20")
    end = time.time() + 240; brief = []
    while time.time() < end and not brief:
        time.sleep(8)
        brief = [m for m in db(ROWS.format(base)) if (m["sourceLabel"] or "").startswith("回来简报")]
    after = {t: db(f"select outcome, outcomeRefId from events where id>{ev} and title='{t}' order by id desc limit 1") for t, _, _ in [(a[1], 0, 0) for a in asks]}
    digested = [t for t, r in after.items() if r and r[0]["outcome"] == "DIGESTED" and brief and r[0]["outcomeRefId"] == brief[0]["id"]]
    drafts = [c for m in brief for c in payload(m).get("actions", []) if c.get("tool") == "send_reply"]
    # Whether each ask deserves a message is the route's business (JEV may drop one as chatter); what is tested is that
    # nothing chat-worthy went out on its own, and everything held came back as one briefing.
    ok = held and not sent_alone and bool(brief) and set(digested) == set(held) and len(brief[0]["text"]) <= 260 and "赵甘霖" not in brief[0]["text"]
    record("回来简报：开会时攒着，会后一次说完", bool(ok), f"攒住={held} 忽略={ignored} 单独发出={sent_alone} 简报={bool(brief)} 并入={digested} 拟回复={len(drafts)} 字数={len(brief[0]['text']) if brief else 0}", brief)


def case_moment_call():
    """A real call on the emulator, hung up after a minute. The caller texted this morning, so the moment is worth a word and the word is about that text."""
    forget("call_ended")
    number = f"139{random.randint(10000000, 99999999)}"
    who, matter, words = random.choice([("物业维修李师傅", "您报修的厨房水管今天下午上门，到之前我给您打电话确认家里有人", ("水管", "物业", "李师傅", "上门")),
                                         ("顺丰快递员", "您有一个到付件今天派送，到楼下给您打电话，请保持电话畅通", ("顺丰", "快递", "到付", "派送")),
                                         ("口腔医院导医", "您预约的周四上午洗牙需要电话确认，稍后给您去电", ("口腔", "洗牙", "预约", "医院"))])
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    broadcast("DEBUG_NOTIFY", app="短信", title=number, text=f"您好，我是{who}，{matter}。")
    time.sleep(25)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    adb("emu", "gsm", "call", number); time.sleep(4); adb("shell", "input", "keyevent", "KEYCODE_CALL"); time.sleep(70)
    adb("emu", "gsm", "cancel", number)
    rows = wait_moment(ev, "call_ended", timeout=150)
    msgs = db(ROWS.format(base))
    text = said([m for m in msgs if (m["sourceLabel"] or "").startswith("时刻")])
    ok = bool(rows) and rows[-1]["outcome"] == "CHAT_SENT" and any(w in text for w in words)
    record("时刻·通话结束，接上他之前发来的事", ok, f"行={[(r['finalRoute'], r['outcome']) for r in rows]} 说了={text[:60]}", msgs)


def case_moment_screenshot():
    """The system's own screenshot key over another app: one moment per screenshot, read by the vision call, and either a
    button or a reasoned silence. A screenshot of Spell Mini itself must not fire at all."""
    signal("screenshot"); forget("screenshot")
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    n = random.randint(10, 28)
    # A screenshot of this app is him showing the assistant around, not asking it for anything.
    focus_app(); time.sleep(2)
    ev0 = db("select coalesce(max(id),0) m from events")[0]["m"]
    adb("shell", "input", "keyevent", "120"); time.sleep(12)
    own = moment_rows(ev0, "screenshot")
    # The real case: a screen of another app with a time and a place on it. The contact editor renders whatever it is
    # handed and has no first-run screen (Messages and Calendar on a fresh emulator both sit on one).
    # An editor left open by an earlier case would swallow the intent, so the contacts task is cleared first.
    adb("shell", "am", "force-stop", "com.google.android.contacts"); adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    adb("shell", "am", "start", "--activity-clear-task", "-a", "android.intent.action.INSERT", "-t", "vnd.android.cursor.dir/contact", "--es", "name", f"'{random.choice(['李总', '陈总', '周老师'])}生日会'",
        "--es", "notes", f"'10月{n}日下午三点 国贸三期80层云酷酒吧 联系人小陈'", "--es", "phone", "13900001111"); time.sleep(5)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    adb("shell", "input", "keyevent", "120"); time.sleep(3)
    rows = wait_moment(ev, "screenshot", timeout=150); time.sleep(8)
    rows = moment_rows(ev, "screenshot")
    signal("screenshot", False); adb("shell", "input", "keyevent", "KEYCODE_BACK"); adb("shell", "input", "keyevent", "KEYCODE_BACK"); adb("shell", "input", "keyevent", "KEYCODE_HOME")
    # What has to hold: the shot was read (the transcription names the party) and answered once, by a message or by a
    # reasoned silence.
    read = bool(rows) and any(k in (rows[0]["text"] or "") for k in ("生日", "云酷", "国贸"))
    ok = not own and len(rows) == 1 and read and (rows[0]["outcome"] in ("CHAT_SENT", "CHAT_SILENT") or rows[0]["finalRoute"] == "ignore")
    record("时刻·截图只触发一次并被读懂", ok, f"截自家界面触发={len(own)} 读到了={read} 行={[(r['finalRoute'], r['outcome'], (r['outcomeNote'] or '')[:30]) for r in rows]}", db(ROWS.format(base)))


def case_moment_home():
    """Coming home is the phone joining the home Wi-Fi: mark the emulator's network as home and reconnect."""
    signal("place"); signal("arrived_home"); forget("arrived_home")
    assert "result=0" in adb("shell", f"am broadcast -a {PKG}.DEBUG_PLACE -p {PKG} --es home AndroidWifi")
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); adb("shell", "svc", "wifi", "disable"); time.sleep(6); adb("shell", "svc", "wifi", "enable")
    rows = wait_moment(ev, "arrived_home", timeout=120)
    signal("arrived_home", False)
    record("时刻·连上家里的 Wi‑Fi 就是到家", bool(rows) and rows[-1]["finalRoute"] in ("chat", "ignore"), f"行={[(r['finalRoute'], r['outcome'], (r['outcomeNote'] or '')[:30]) for r in rows]}", [])


def case_moment_meeting():
    """A calendar event a little over the lead time away: the exact alarm fires the pre-meeting moment, and what it says comes from the notifications about that meeting."""
    clear_events(); forget("meeting_soon")
    topic = random.choice(["Ocean评测口径对齐", "手机端Agent选型评审", "Q4评测计划同步"])
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    who = random.choice(["王磊", "韩梅", "赵强"])
    broadcast("DEBUG_NOTIFY", app="飞书", title=who, text=f"{topic}那个会之前，你把三个模型的工具调用成功率数据发我一下，我要放进材料里")
    time.sleep(30)
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    add_event(topic, 10 * 60 + 50)
    rows = wait_moment(ev, "meeting_soon", timeout=200)
    text = said([m for m in db(ROWS.format(base)) if (m["sourceLabel"] or "").startswith("时刻")])
    clear_events()
    ok = bool(rows) and rows[-1]["outcome"] == "CHAT_SENT" and who in text
    record("时刻·会前十分钟，带上和这场会有关的事", ok, f"行={[(r['finalRoute'], r['outcome']) for r in rows]} 说了={text[:70]}", db(ROWS.format(base)))


def add_source(template, **extras):
    parts = [f"am broadcast -a {PKG}.DEBUG_SOURCE -p {PKG} --es template '{template}'"] + [f"--es {k} '{v}'" for k, v in extras.items()]
    assert "result=0" in adb("shell", " ".join(parts))


def case_sources_every_kind():
    """One source of each kind the form offers: a JSON list, a watched number, a calendar, a push topic and a mailbox (a fake server on this machine)."""
    before = {s["id"] for s in sources_in_store()}
    ev = db("select coalesce(max(id),0) m from events")[0]["m"]
    topic = f"spellmini-e2e-{random.randint(10**11, 10**12)}"
    server = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_imap.py")])
    try:
        add_source("UFC"); add_source("汇率"); add_source("节假日"); add_source("ntfy", url=f"https://ntfy.sh/{topic}", name=f"推送{R}")
        add_source("邮箱", url="imap://10.0.2.2:1143", name=f"邮箱{R}", user="me@example.com", secret="secret-code")
        time.sleep(40)
        subprocess.run(["curl", "-s", "-m", "15", "-H", "Title: 评测任务", "-d", f"judge run {R} 完成：失败 3 格，等你确认是否发布", f"https://ntfy.sh/{topic}"], capture_output=True)
        broadcast("DEBUG_POLL"); time.sleep(50)
    finally:
        server.terminate()
    new = [s for s in sources_in_store() if s["id"] not in before]
    by = {s["kind"] + ("#" if s.get("config", {}).get("value") else ""): s for s in new}
    rows = db(f"select appName, title, text from events where id>{ev} and (pkg like 'feed.u%' or pkg like 'push.%')")
    mail = [r for r in rows if r["appName"].endswith(f"邮箱{R}")]
    pushed = [r for r in rows if r["appName"].endswith(f"推送{R}")]
    decoded = any("CA1831" in r["text"] and "出票成功" in r["text"] for r in mail) and any("信用卡" in r["text"] and r["title"] == "招商银行" for r in mail)
    leaked = any("secret-code" in json.dumps(s, ensure_ascii=False) for s in new)
    ok = (by.get("json", {}).get("taken", 0) >= 1 and by.get("json#", {}).get("config", {}).get("last") and by.get("ics", {}).get("taken", 0) >= 1
          and bool(pushed) and decoded and not leaked and not any(s.get("lastError") for s in new))
    record("外部·每种接入各一个", bool(ok), f"JSON列表={by.get('json', {}).get('taken')} 盯一个数={by.get('json#', {}).get('config', {}).get('last')} 日历={by.get('ics', {}).get('taken')} "
           f"推送到达={len(pushed)} 邮件解码={decoded} 密钥进库={leaked} 出错={[(s['name'], s['lastError'][:30]) for s in new if s.get('lastError')]}", [])
    for s in new: send(f"取消订阅 #{s['id']}"); wait_quiet(db("select coalesce(max(id),0) m from messages")[0]["m"] - 1, timeout=60)


def case_signals_page():
    """The page exists, shows what JEV would be told about this moment, and lists every group."""
    focus_app(); time.sleep(1)
    xml = ui()
    ball = re.findall(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    tap(979, 200); time.sleep(2.5)
    tab = find(ui(), text="信号")  # not tap_text: that goes through focus_app, which backs out of the hub
    if tab: tap(*tab[0])
    time.sleep(3)
    wanted_texts = ("JEV 现在看到的你", "此刻的状态", "这个人对你多重要", "时刻", "外面的流", "添加一个源")
    found = set()
    for _ in range(36):
        page = ui()
        found |= {t for t in wanted_texts if f'text="{t}"' in page}
        if len(found) == len(wanted_texts): break
        adb("shell", "input", "swipe", "540", "1800", "540", "700", "250"); time.sleep(0.5)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    record("信号页·四组和源管理都在", len(found) == len(wanted_texts), f"看到={sorted(found)}", [])


SPECIAL = [
    ("提醒 + 撤销", case_reminder_and_undo), ("关注主题", case_follow), ("通知·有截止时间", case_trigger_deadline),
    ("通知·工作群被 @", case_trigger_mention), ("通知·后台动作变按钮", case_trigger_button), ("反馈·别再提这类", case_feedback_rule),
    ("通知·拟好回复一键发出", case_reply), ("通知·你已经点开了", case_already_opened),
    ("只问不做", case_lookup_only), ("定期任务 + 撤销", case_recurring_task), ("盯着·订阅源", case_watch_feed), ("分享进来的文字", case_share_text),
    ("该来没来·记下等下文的事", case_open_loop), ("交办一件活·成品", case_job), ("定时任务到点执行", case_scheduled_task),
    ("订阅·预置源", case_sources_seeded), ("订阅·分流", case_item_routes), ("订阅·碰到手头的事", case_item_touches_work),
    ("订阅·聊天里订阅", case_subscribe_in_chat), ("订阅·真实源能读", case_poll_live),
    ("状态·开会时", case_context_volume), ("状态·只震动", case_vibrate_not_quiet), ("回来简报", case_digest), ("时刻·通话结束", case_moment_call), ("时刻·截图", case_moment_screenshot), ("时刻·到家", case_moment_home),
    ("时刻·会前十分钟", case_moment_meeting), ("外部·每种接入", case_sources_every_kind), ("信号页", case_signals_page),
]

grant_for_signals()
wanted = sys.argv[1:]
def selected(name): return not wanted or any(w in name for w in wanted)

for case in USER_CASES:
    if selected(case[0]): run_user_case(*case)
for name, fn in SPECIAL:
    if selected(name): fn()

json.dump(results, open(os.path.join(WORK, "result.json"), "w"), ensure_ascii=False, indent=1)
print(f"{sum(r['ok'] for r in results)}/{len(results)} passed")
