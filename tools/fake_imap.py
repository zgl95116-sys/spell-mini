"""A tiny IMAP server with two messages, to exercise the app's reader: multipart base64 UTF-8, and single-part quoted-printable GBK HTML."""
import socket, base64, quopri, re, threading
def b64(s,cs="utf-8"): return base64.b64encode(s.encode(cs)).decode()
m1_text="尊敬的旅客：您预订的 CA1831 航班（北京首都 T3 → 上海虹桥 T2）已出票。\r\n出发：2026年10月3日 08:30，座位 31A，票号 999-1234567890。\r\n请提前 2 小时到达机场办理值机。"
m1=("From: =?UTF-8?B?%s?= <service@airchina.example>\r\nSubject: =?UTF-8?B?%s?=\r\n =?UTF-8?B?%s?=\r\nDate: Tue, 22 Sep 2026 03:40:00 +0800\r\nContent-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n"%(b64("中国国航"),b64("【出票成功】CA1831 "),b64("10月3日 北京-上海")),
    "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n%s\r\n--b1\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n%s\r\n--b1--\r\n"%(base64.encodebytes(m1_text.encode()).decode().replace("\n","\r\n"),base64.encodebytes(("<html><body><p>"+m1_text+"</p></body></html>").encode()).decode().replace("\n","\r\n")))
m2_html="<html><body><h3>信用卡账单</h3><p>您尾号 8888 的信用卡 9 月账单已出：应还 3,256.40 元，到期还款日 10 月 8 日。</p></body></html>"
m2=("From: \"=?GBK?B?%s?=\" <bill@bank.example>\r\nSubject: =?GBK?Q?%s?=\r\nDate: Tue, 22 Sep 2026 03:41:00 +0800\r\nContent-Type: text/html; charset=GBK\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n"%(b64("招商银行","gbk"),quopri.encodestring("9月信用卡账单".encode("gbk"),header=True).decode().replace(" ","_")),
    quopri.encodestring(m2_html.encode("gbk")).decode().replace("\n","\r\n"))
MSGS={101:m1,102:m2}
def serve(conn):
    f=conn.makefile("rwb"); w=lambda s:(f.write(s.encode("latin-1") if isinstance(s,str) else s),f.flush())
    w("* OK fake imap ready\r\n")
    while True:
        line=f.readline()
        if not line: break
        tag,_,cmd=line.decode("latin-1").strip().partition(" "); up=cmd.upper()
        if up.startswith("LOGIN"): w(f"{tag} OK LOGIN completed\r\n" if "secret-code" in cmd else f"{tag} NO wrong password\r\n")
        elif up.startswith("ID"): w("* ID NIL\r\n"+f"{tag} OK\r\n")
        elif up.startswith("SELECT"): w("* 2 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT 103] next\r\n"+f"{tag} OK [READ-WRITE] done\r\n")
        elif up.startswith("UID SEARCH"):
            lo=int(re.search(r"UID (\d+):",up).group(1)); w("* SEARCH "+" ".join(str(u) for u in MSGS if u>=lo)+"\r\n"+f"{tag} OK\r\n")
        elif up.startswith("UID FETCH"):
            uid=int(up.split()[2]); h,b=MSGS[uid]; hb=h.encode("latin-1") if uid==101 else h.encode("latin-1"); bb=b.encode("latin-1")[:8192]
            w(f"* {uid-100} FETCH (UID {uid} BODY[HEADER.FIELDS (SUBJECT FROM DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] {{{len(hb)}}}\r\n".encode()+hb+f" BODY[TEXT]<0> {{{len(bb)}}}\r\n".encode()+bb+b")\r\n"+f"{tag} OK FETCH done\r\n".encode())
        elif up.startswith("LOGOUT"): w("* BYE\r\n"+f"{tag} OK\r\n"); break
        else: w(f"{tag} BAD unknown\r\n")
    conn.close()
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(("0.0.0.0",1143)); s.listen(5)
while True:
    c,_=s.accept(); threading.Thread(target=serve,args=(c,),daemon=True).start()
