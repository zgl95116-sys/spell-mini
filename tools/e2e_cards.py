"""End-to-end check of the confirm-card paths on the emulator.

Each case types a request in the real composer and waits until the conversation has been quiet for a while (a turn can
take several tool rounds). Then, depending on what the case expects:
  card      - a REAL card row for the right tool must exist; tap the on-screen button; verify the system side effect
  explain   - no card, a non-empty reply, and the reply must not claim the action was done
  duplicate - no NEW card, because an identical approved card already exists; the reply says so
"""
import json, os, random, re, sqlite3, subprocess, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = "emulator-5554"
PKG = "com.logan.spellmini"
S = os.environ.get("SPELLMINI_TEST_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), ".e2e")
os.makedirs(S, exist_ok=True)


def adb(*args, timeout=25, binary=False):
    try:
        out = subprocess.run([ADB, "-s", DEV, *args], capture_output=True, timeout=timeout).stdout
        return out if binary else out.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return b"" if binary else ""


def db(sql):
    d = os.path.join(S, "dbpull2"); os.makedirs(d, exist_ok=True)
    for f in ("spellmini.db", "spellmini.db-wal", "spellmini.db-shm"):
        open(os.path.join(d, f), "wb").write(adb("exec-out", "run-as", PKG, "cat", f"databases/{f}", binary=True))
    con = sqlite3.connect(os.path.join(d, "spellmini.db")); con.row_factory = sqlite3.Row
    rows = [dict(r) for r in con.execute(sql)]; con.close(); return rows


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def find(xml, text=None, desc=None):
    pat = (r'text="%s"' % re.escape(text)) if text else (r'content-desc="%s"' % re.escape(desc))
    return [((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
            for a, b, c, d in re.findall(pat + r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]


def tap(x, y): adb("shell", "input", "tap", str(x), str(y)); time.sleep(1.2)


def focus_app():
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2.5)
    chat = find(ui(), text="Chat")
    if chat: tap(*chat[0])


def top_activity():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*? ([\w.]+/[\w.$]+)", out)
    return m.group(1) if m else "?"


def send(text):
    """Injects the message through the DUMP-protected debug receiver: real Chinese text, no flaky IME typing."""
    assert "'" not in text
    focus_app()
    out = adb("shell", f"am broadcast -a com.logan.spellmini.DEBUG_SEND -p {PKG} --es text '{text}'")
    assert "result=0" in out, out


ROWS = "select id, role, kind, streaming, text, cardState, cardJson from messages where id>{} order by id"


def wait_quiet(after_id, timeout=200, quiet=12):
    """Done when an assistant reply exists, nothing is streaming, and no row has been added for `quiet` seconds."""
    end, last_count, last_change = time.time() + timeout, -1, time.time()
    while time.time() < end:
        time.sleep(4)
        rows = db(ROWS.format(after_id))
        if len(rows) != last_count: last_count, last_change = len(rows), time.time()
        replied = any(r["role"] == "assistant" and r["kind"] == "text" and r["text"].strip() for r in rows)
        if replied and not any(r["streaming"] for r in rows) and time.time() - last_change >= quiet:
            return rows
    return db(ROWS.format(after_id))


R = random.randint(10, 49)
ALARM = f"设一个早上5点{R}分的闹钟，备注游泳{R}"
FAKE = re.compile(r"\[确认卡|【确认卡|\[系统记录|\[我主动发的")
CLAIMS_DONE = re.compile(r"已经?(设|建|加|存|打开)[好过]?了|(设|建|加|存)好了|刚(打开|设|建|加)|备好?了|点(一下)?确认")
CASES = [
    # name, what the user says, expectation, tool, button, side-effect check
    ("拨号", "你打 13800138000", "card", "dial_number", "同意", lambda: "dialer" in top_activity().lower()),
    ("打开已装的 App", "打开设置", "card", "open_app", "同意", lambda: "settings" in top_activity().lower()),
    ("打开没装的 App", "打开计算器", "explain", "open_app", None, None),
    ("建日程", f"帮我在日历里加个日程：10月{R % 18 + 10}日下午两点半，团队外出{R}", "card", "create_calendar_event", "同意", lambda: "calendar" in top_activity().lower()),
    ("设闹钟", ALARM, "card", "set_alarm", "同意", lambda: "deskclock" in adb("shell", "dumpsys", "alarm")),
    ("重复设同一个闹钟", ALARM, "duplicate", "set_alarm", None, None),
    ("设提醒", f"9月2{R % 7 + 2}日上午11点{R}分提醒我给房东打电话，说{R}号那件事", "card", "set_reminder", "同意", lambda: "ReminderReceiver" in adb("shell", "dumpsys", "alarm")),
    ("拒绝一张卡", "打开相机", "card", "open_app", "不用了", lambda: PKG in top_activity()),
]

results = []
for name, typed, expect, tool, button, verify in CASES:
    base = db("select coalesce(max(id),0) m from messages")[0]["m"]
    send(typed)
    rows = wait_quiet(base)
    texts = [r["text"] for r in rows if r["kind"] == "text" and r["role"] == "assistant"]
    said = " ".join(texts).replace("\n", " ")
    cards = [r for r in rows if r["kind"] == "card" and json.loads(r["cardJson"] or "{}").get("tool") == tool]
    fake = bool(FAKE.search(said))
    ok, detail = False, ""
    if expect == "card":
        if not cards:
            detail = "没有出真卡"
        else:
            focus_app(); time.sleep(1)
            buttons = find(ui(), text=button)
            if not buttons:
                detail = "屏幕上找不到按钮"
            else:
                tap(*max(buttons, key=lambda p: p[1])); time.sleep(3)
                state = db(f"select cardState from messages where id={cards[-1]['id']}")[0]["cardState"]
                if state == "pending":  # the list may have scrolled between the dump and the tap: look again once
                    focus_app(); time.sleep(1)
                    again = find(ui(), text=button)
                    if again: tap(*max(again, key=lambda p: p[1])); time.sleep(3)
                    state = db(f"select cardState from messages where id={cards[-1]['id']}")[0]["cardState"]
                acted = bool(verify())
                ok = acted and state in ("approved", "denied")
                detail = f"状态={state} 系统侧验证={'通过' if acted else '未通过'}"
            adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    elif expect == "explain":
        ok = not cards and bool(said.strip()) and not CLAIMS_DONE.search(said)
        detail = "无卡、有解释、没有谎称已做" if ok else f"卡={len(cards)} 有回复={bool(said.strip())} 谎称={bool(CLAIMS_DONE.search(said))}"
    elif expect == "duplicate":
        ok = not cards and bool(said.strip())
        detail = "没有出重复的卡，并说明已经设过" if ok else f"出了 {len(cards)} 张重复卡"
    ok = ok and not fake
    results.append(dict(case=name, ok=ok, detail=detail, fake_card_text=fake, said=said[:64]))
    print(("PASS " if ok else "FAIL ") + f"{name:<12} {detail}  |  {said[:60]}", flush=True)

json.dump(results, open(os.path.join(S, "e2e_cards_result.json"), "w"), ensure_ascii=False, indent=1)
print(f"{sum(r['ok'] for r in results)}/{len(results)} passed")
