"""Feedback context, snapshot, visibility, and transaction checks on isolated QA DB."""
from __future__ import annotations

import json
import os
import sqlite3
import time
import atexit
from pathlib import Path
from urllib.parse import urlencode, urlsplit

from http_client import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "target" / "feedback-context-checks.json"


def isolated_env():
    base = os.environ.get("ROAM_BASE", "").rstrip("/")
    db_s, data_s = os.environ.get("ROAM_QA_DB", ""), os.environ.get("ROAM_DATA_DIR", "")
    if not base or not db_s or not data_s:
        raise SystemExit("拒绝运行：必须显式设置 ROAM_BASE、ROAM_QA_DB 和 ROAM_DATA_DIR。")
    u = urlsplit(base)
    if u.scheme != "http" or u.hostname not in {"127.0.0.1", "localhost"} or not u.port or u.port == 8818:
        raise SystemExit("拒绝运行：仅允许本机非 8818 HTTP 实例。")
    db, data = Path(db_s).resolve(), Path(data_s).resolve()
    work = (ROOT / "work").resolve()
    if db == (ROOT / "data" / "guide.db").resolve() or data == (ROOT / "data").resolve():
        raise SystemExit("拒绝运行：拒绝正式 data/guide.db。")
    try:
        db.relative_to(work); data.relative_to(work)
    except ValueError:
        raise SystemExit("拒绝运行：隔离数据库和媒体目录必须在项目 work/ 下。")
    if db != (data / "guide.db").resolve() or not db.is_file():
        raise SystemExit("拒绝运行：ROAM_QA_DB 必须是已存在的 ROAM_DATA_DIR/guide.db。")
    return base, db


BASE, DB = isolated_env()
STAMP = str(time.time_ns())[-10:]
MARK = "QA-FEEDBACK-" + STAMP
checks: list[str] = []
user_ids: list[int] = []
fixture_ids: dict[str, list[int]] = {k: [] for k in ("cities", "spots", "guides", "comments", "foods", "posts", "social_comments", "trips", "routes", "feedback")}
installed_triggers: list[str] = []


def check(name: str, condition: bool):
    if not condition:
        raise AssertionError(name)
    checks.append(name)


def sql_one(sql: str, *args):
    with sqlite3.connect(DB, timeout=10) as conn:
        conn.execute("PRAGMA foreign_keys=ON")
        return conn.execute(sql, args).fetchone()


def sql_all(sql: str, *args):
    with sqlite3.connect(DB, timeout=10) as conn:
        conn.execute("PRAGMA foreign_keys=ON")
        return conn.execute(sql, args).fetchall()


class Client:
    def __init__(self):
        self.s = requests.Session()
        self.csrf = self.s.get(BASE + "/api/session").json()["csrf"]

    def raw(self, method, path, body=None, *, csrf=True):
        headers = {"X-CSRF-Token": self.csrf} if csrf else {}
        return self.s.request(method, BASE + path, json=body, headers=headers)

    def call(self, method, path, body=None, status=200, *, csrf=True):
        r = self.raw(method, path, body, csrf=csrf)
        if r.status_code != status:
            raise AssertionError(f"{method} {path}: expected {status}, got {r.status_code}: {r.text[:300]}")
        if not r.content:
            return None
        try:
            value = r.json()
        except Exception:
            return r.content
        if isinstance(value, dict) and value.get("csrf"):
            self.csrf = value["csrf"]
        return value

    def login(self, username, password):
        return self.call("POST", "/api/auth/login", {"username": username, "password": password})


def register(c: Client, tag: str):
    username, password = f"qaf_{STAMP}_{tag}", "FeedbackQA!2026"
    user = c.call("POST", "/api/auth/register", {"username": username, "password": password})["user"]
    user_id = int(user["id"])
    user_ids.append(user_id)
    logged = Client(); logged.login(username, password)
    return user_id, logged


def create_fixtures(owner_id: int):
    with sqlite3.connect(DB, timeout=10) as conn:
        conn.execute("PRAGMA foreign_keys=ON")
        c = conn.cursor()
        c.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',1,?,?,?,?,?,?)",
                  ("qaf-" + STAMP, MARK + " city", "QA", "QA tagline", "QA description", "/assets/nanyang-hero.jpg"))
        city = c.lastrowid; fixture_ids["cities"].append(city)
        c.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',0,?,?,?,?,?,?)",
                  ("qaf-hidden-" + STAMP, MARK + " hidden city", "QA", "QA tagline", "QA description", "/assets/nanyang-hero.jpg"))
        hidden_city = c.lastrowid; fixture_ids["cities"].append(hidden_city)
        def spot(title, city_id, published=1):
            c.execute("INSERT INTO spots(city_id,title,intro,description,image,published) VALUES(?,?,?,?,?,?)",
                      (city_id,title,"QA intro","QA description","",published))
            fixture_ids["spots"].append(c.lastrowid); return c.lastrowid
        sp = spot(MARK + " spot", city)
        hidden_sp = spot(MARK + " hidden spot", city, 0)
        city_sp = spot(MARK + " hidden city spot", hidden_city, 1)
        blank_sp = spot("", city)
        def guide(title, published=1):
            c.execute("INSERT INTO guides(city_id,title,intro,image,published) VALUES(?,?,?,?,?)", (city,title,"QA intro","",published))
            fixture_ids["guides"].append(c.lastrowid); return c.lastrowid
        gd = guide(MARK + " guide")
        gd2 = guide(MARK + " other guide")
        hidden_gd = guide(MARK + " hidden guide", 0)
        long_body = "<script>" + ("x" * 260)
        c.execute("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)", (gd,owner_id,long_body))
        gc = c.lastrowid; fixture_ids["comments"].append(gc)
        c.execute("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)", (gd2,owner_id,"wrong parent comment"))
        wrong_gc = c.lastrowid; fixture_ids["comments"].append(wrong_gc)
        c.execute("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)", (gd,owner_id,"x"*199+"😀"+"tail"))
        emoji_gc = c.lastrowid; fixture_ids["comments"].append(emoji_gc)
        c.execute("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)", (gd,owner_id,"x"*198+"😀"+"tail"))
        boundary_gc = c.lastrowid; fixture_ids["comments"].append(boundary_gc)
        c.execute("INSERT INTO comments(guide_id,user_id,body) VALUES(?,?,?)", (hidden_gd,owner_id,"hidden guide comment"))
        hidden_gc = c.lastrowid; fixture_ids["comments"].append(hidden_gc)
        c.execute("INSERT INTO foods(city_id,title,category,intro,description,published) VALUES(?,?,'小吃','QA intro','QA description',1)", (city,MARK+" food"))
        food = c.lastrowid; fixture_ids["foods"].append(food)
        c.execute("INSERT INTO foods(city_id,title,category,intro,description,published) VALUES(?,?,'小吃','QA intro','QA description',0)", (city,MARK+" hidden food"))
        hidden_food = c.lastrowid; fixture_ids["foods"].append(hidden_food)
        c.execute("INSERT INTO social_posts(user_id,city_id,category,title,body,status) VALUES(?,?,'游记',?,?,'PUBLISHED')", (owner_id,city,MARK+" post","QA post body enough"))
        post = c.lastrowid; fixture_ids["posts"].append(post)
        c.execute("INSERT INTO social_posts(user_id,city_id,category,title,body,status) VALUES(?,?,'游记',?,?,'HIDDEN')", (owner_id,city,MARK+" hidden post","QA hidden post body enough"))
        hidden_post = c.lastrowid; fixture_ids["posts"].append(hidden_post)
        c.execute("INSERT INTO social_comments(post_id,user_id,body) VALUES(?,?,?)", (post,owner_id,"<img src=x onerror=1>" + "p"*230))
        pc = c.lastrowid; fixture_ids["social_comments"].append(pc)
        c.execute("INSERT INTO social_comments(post_id,user_id,body) VALUES(?,?,?)", (hidden_post,owner_id,"hidden post comment"))
        hidden_pc = c.lastrowid; fixture_ids["social_comments"].append(hidden_pc)
        for title,status,blocked in ((MARK+" route","PUBLISHED",0),(MARK+" private route","PRIVATE",0),(MARK+" blocked route","PUBLISHED",1)):
            c.execute("INSERT INTO trips(user_id,title,days) VALUES(?,?,1)", (owner_id,title+" source"))
            trip_id = c.lastrowid; fixture_ids["trips"].append(trip_id)
            c.execute("INSERT INTO shared_routes(source_trip_id,user_id,title,days,status,blocked) VALUES(?,?,?,?,?,?)", (trip_id,owner_id,title,1,status,blocked))
            fixture_ids["routes"].append(c.lastrowid)
        conn.commit()
    return {"city":city,"hidden_city":hidden_city,"spot":sp,"hidden_spot":hidden_sp,"hidden_city_spot":city_sp,"blank_spot":blank_sp,
            "guide":gd,"other_guide":gd2,"hidden_guide":hidden_gd,"guide_comment":gc,"wrong_guide_comment":wrong_gc,"emoji_guide_comment":emoji_gc,"boundary_guide_comment":boundary_gc,"hidden_guide_comment":hidden_gc,
            "food":food,"hidden_food":hidden_food,"post":post,"hidden_post":hidden_post,"post_comment":pc,"hidden_post_comment":hidden_pc,
            "route":fixture_ids["routes"][0],"private_route":fixture_ids["routes"][1],"blocked_route":fixture_ids["routes"][2]}


def context(c: Client, path: str, status=200):
    result=c.call("GET", "/api/feedback/context?" + urlencode({"path":path}), status=status)
    if status==200 and isinstance(result,dict):
        check("context response contains only the declared safe snapshot fields",set(result)=={"page_path","page_title","target_excerpt"})
    return result


def submit(c: Client, path: str, body=None, status=200, extra=None):
    payload = {"category":"内容纠错","body":body or (MARK + " feedback"),"page_path":path}
    if extra: payload.update(extra)
    return c.call("POST", "/api/me/feedback", payload, status=status)


def main():
    guest = Client()
    admin = Client(); admin.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    alice_id, alice = register(Client(), "alice")
    _, bob = register(Client(), "bob")
    ids = create_fixtures(alice_id)
    try:
        # Anonymous endpoint is public, while personal feedback remains authenticated and CSRF-protected.
        for path,title in (("/","漫游首页"),("/destinations","目的地"),("/guides","旅行攻略"),("/food","美食探索"),("/community","旅行社区"),("/routes","路线广场"),("/search","站内搜索"),("/recommend","旅行推荐"),("/trips","我的行程"),("/me","个人中心")):
            data=context(guest,path)
            check("general public path " + path, data["page_path"]==path and data["page_title"]==title and data["target_excerpt"]=="")
        plain=submit(alice,"",MARK+" plain")
        fixture_ids["feedback"].append(int(plain["id"]))
        check("empty path keeps ordinary feedback", plain["id"]>0)
        unauth=guest.call("GET","/api/me/feedback",status=401)
        check("personal feedback requires authentication", isinstance(unauth,dict))
        no_csrf=alice.raw("POST","/api/me/feedback",{"category":"内容纠错","body":MARK+" csrf","page_path":""},csrf=False)
        check("feedback submit enforces CSRF",no_csrf.status_code==403)
        forbidden=alice.call("GET","/api/admin/feedback",status=403)
        check("admin feedback listing is forbidden to ordinary users",isinstance(forbidden,dict))

        good_paths=[f"/spot/{ids['spot']}",f"/guide/{ids['guide']}",f"/destination/qaf-{STAMP}",f"/food/{ids['food']}",f"/community/post/{ids['post']}",f"/routes/{ids['route']}"]
        for path in good_paths:
            data=context(guest,path)
            check("public content context " + path,data["page_path"]==path and bool(data["page_title"]) and data["target_excerpt"]=="")
        guide_comment=context(guest,f"/guide/{ids['guide']}#comment-{ids['guide_comment']}")
        check("guide comment target has parent title and 200 character excerpt",guide_comment["page_title"].startswith("攻略 · ") and len(guide_comment["target_excerpt"])==200)
        emoji_comment=context(guest,f"/guide/{ids['guide']}#comment-{ids['emoji_guide_comment']}")
        check("truncation before a boundary emoji backs off instead of splitting its surrogate pair",emoji_comment["target_excerpt"]=="x"*199 and not any(0xD800<=ord(ch)<=0xDFFF for ch in emoji_comment["target_excerpt"]))
        boundary_comment=context(guest,f"/guide/{ids['guide']}#comment-{ids['boundary_guide_comment']}")
        check("emoji fitting exactly within 200 UTF-16 units remains whole",boundary_comment["target_excerpt"]=="x"*198+"😀" and not any(0xD800<=ord(ch)<=0xDFFF for ch in boundary_comment["target_excerpt"]))
        post_comment=context(guest,f"/community/post/{ids['post']}#comment-{ids['post_comment']}")
        check("community comment excerpt is capped at 200",len(post_comment["target_excerpt"])==200)
        blank=context(guest,f"/spot/{ids['blank_spot']}")
        check("blank public item title retains fixed type label",blank["page_title"]=="景点 · ")

        malformed=["", "spot/1", "//example.com", "/spot/0", "/spot/01", "/spot/1/2", "/spot/1?x=1", "/spot/1#comment-1", "/spot/%31", "/spot/%2f%2fevil", "/spot/1%3Fq=x", "/spot/1\\x", "/guide/1#comment-0", "/guide/1#comment-2#x", "/community/post/1#comment-1/", "/spot/999999999999999999999999999999999999999999", "/trip/1", "/feedback", "/admin"]
        for path in malformed:
            context(guest,path,400)
        check("invalid, encoded, query, fragment and non-whitelisted paths return 400",True)

        hidden_paths=[f"/spot/{ids['hidden_spot']}",f"/spot/{ids['hidden_city_spot']}",f"/guide/{ids['hidden_guide']}",f"/guide/{ids['guide']}#comment-{ids['wrong_guide_comment']}",f"/guide/{ids['hidden_guide']}#comment-{ids['hidden_guide_comment']}",f"/destination/qaf-hidden-{STAMP}",f"/food/{ids['hidden_food']}",f"/community/post/{ids['hidden_post']}",f"/community/post/{ids['post']}#comment-{ids['hidden_post_comment']}",f"/routes/{ids['private_route']}",f"/routes/{ids['blocked_route']}","/spot/999999999", "/guide/999999999", "/destination/missing-qaf", "/food/999999999", "/community/post/999999999", "/routes/999999999"]
        for path in hidden_paths:
            result=context(guest,path,404)
            serialized=json.dumps(result,ensure_ascii=False)
            for secret in (MARK+" hidden",):
                check("unavailable source does not expose hidden title " + path,secret not in serialized)
        # Hidden source titles are also absent from the raw response body.
        for path,secret in ((f"/spot/{ids['hidden_spot']}",MARK+" hidden spot"),(f"/destination/qaf-hidden-{STAMP}",MARK+" hidden city"),(f"/community/post/{ids['hidden_post']}",MARK+" hidden post"),(f"/routes/{ids['private_route']}",MARK+" private route")):
            r=guest.raw("GET","/api/feedback/context?"+urlencode({"path":path}))
            check("404 body redacts " + secret,r.status_code==404 and secret not in r.text)

        # Server-resolved snapshot ignores spoofed client labels/excerpts.
        spoof={"page_title":"SPOOF TITLE","target_excerpt":"SPOOF EXCERPT","source_title":"SPOOF SOURCE"}
        saved=submit(alice,f"/guide/{ids['guide']}#comment-{ids['guide_comment']}",MARK+" snapshot",extra=spoof)
        fixture_ids["feedback"].append(int(saved["id"]))
        row=sql_one("SELECT page_path,page_title,target_excerpt FROM feedback WHERE id=?",saved["id"])
        check("server stores canonical title and excerpt, ignoring spoofed fields",row[0]==f"/guide/{ids['guide']}#comment-{ids['guide_comment']}" and row[1]==guide_comment["page_title"] and row[2]==guide_comment["target_excerpt"])
        plain=submit(alice,"",MARK+" no snapshot",extra=spoof);fixture_ids["feedback"].append(int(plain["id"]))
        check("empty source stores empty page snapshot",sql_one("SELECT page_path,page_title,target_excerpt FROM feedback WHERE id=?",plain["id"])==("","",""))
        alice_rows=alice.call("GET","/api/me/feedback")
        check("owner list includes frozen title excerpt and source_available",any(x["id"]==saved["id"] and x["page_title"]==guide_comment["page_title"] and x["target_excerpt"]==guide_comment["target_excerpt"] and x["source_available"] is True for x in alice_rows))
        bob_rows=bob.call("GET","/api/me/feedback")
        check("another user cannot read owner feedback",all(x["id"]!=saved["id"] for x in bob_rows))
        bob.call("GET",f"/api/me/feedback/{saved['id']}",status=404)
        category_bad=alice.raw("POST","/api/me/feedback",{"category":"举报","body":MARK+" bad category","page_path":""})
        check("only the three supported categories are accepted",category_bad.status_code==400)
        submit(alice,f"/guide/{ids['guide']}?q=x",MARK+" bad path",status=400)
        submit(alice,"/spot/999999999",MARK+" missing target",status=404)

        # A reported target can be unpublished after submission; its original snapshot remains visible.
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute("UPDATE guides SET published=0 WHERE id=?",(ids["guide"],));conn.commit()
        stale=context(guest,f"/guide/{ids['guide']}",404)
        own_after=alice.call("GET","/api/me/feedback")
        frozen=next(x for x in own_after if x["id"]==saved["id"])
        check("unpublished target returns 404 without revealing current title while stored snapshot stays intact and unavailable",(MARK+" guide") not in json.dumps(stale,ensure_ascii=False) and frozen["page_title"]==guide_comment["page_title"] and frozen["target_excerpt"]==guide_comment["target_excerpt"] and frozen["source_available"] is False)
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute("UPDATE guides SET published=1 WHERE id=?",(ids["guide"],));conn.commit()

        # Admin search includes snapshot title and preserves the existing 20-row pagination.
        ids_for_page=[]
        for i in range(21):
            item=submit(alice,"",f"{MARK} page row {i}")
            ids_for_page.append(int(item["id"]));fixture_ids["feedback"].append(int(item["id"]))
        query=MARK+" page row"
        first=admin.call("GET","/api/admin/feedback?"+urlencode({"q":query,"page":1}))
        second=admin.call("GET","/api/admin/feedback?"+urlencode({"q":query,"page":2}))
        check("admin search remains paginated at 20 rows",first["total"]==21 and len(first["items"])==20 and second["total"]==21 and len(second["items"])==1 and first["page"]==1 and second["page"]==2)
        title_query=guide_comment["page_title"].split(" · ",1)[-1]
        searched=admin.call("GET","/api/admin/feedback?"+urlencode({"q":title_query,"page":1}))
        check("admin search finds feedback by frozen target title",any(x["id"]==saved["id"] for x in searched["items"]))
        admin_saved=next((x for x in searched["items"] if x["id"]==saved["id"]),None)
        check("admin list receives the same snapshot and availability fields",admin_saved is not None and admin_saved["page_title"]==guide_comment["page_title"] and admin_saved["target_excerpt"]==guide_comment["target_excerpt"] and admin_saved["source_available"] is True)

        # Existing account policy guards: forced-password users cannot submit until they rotate credentials.
        bob_id=sql_one("SELECT id FROM users WHERE username LIKE ?",f"qaf_{STAMP}_bob")[0]
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute("UPDATE users SET must_change_password=1 WHERE id=?",(bob_id,));conn.commit()
        forced=bob.raw("POST","/api/me/feedback",{"category":"内容纠错","body":MARK+" forced password","page_path":""})
        check("temporary-password guard blocks feedback mutation",forced.status_code==409)
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute("UPDATE users SET must_change_password=0 WHERE id=?",(bob_id,));conn.commit()

        # Preload only this QA user's rows to test the documented 100-record cap without 100 HTTP inserts.
        existing_count=sql_one("SELECT count(*) FROM feedback WHERE user_id=?",alice_id)[0]
        with sqlite3.connect(DB,timeout=10) as conn:
            for n in range(max(0,99-existing_count)):
                cur=conn.execute("INSERT INTO feedback(user_id,category,body) VALUES(?,'使用问题',?)",(alice_id,f"{MARK} limit seed {n}"))
                fixture_ids["feedback"].append(cur.lastrowid)
            conn.commit()
        at_limit=submit(alice,"",MARK+" limit final")
        fixture_ids["feedback"].append(int(at_limit["id"]))
        over_limit=alice.raw("POST","/api/me/feedback",{"category":"使用问题","body":MARK+" over limit","page_path":""})
        check("feedback accepts the 100th owner record and rejects the 101st",sql_one("SELECT count(*) FROM feedback WHERE user_id=?",alice_id)[0]==100 and over_limit.status_code==400)

        # A successful reply emits exactly one correctly addressed notification and a successful audit row.
        before_notices=sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback'",alice_id)[0]
        reply=MARK+" reply"
        admin.call("PUT",f"/api/admin/feedback/{saved['id']}",{"status":"RESOLVED","admin_reply":reply})
        fixture_ids["feedback"].append(int(saved["id"]))
        notice=sql_one("SELECT user_id,kind,body FROM notifications WHERE user_id=? AND kind='feedback' AND body=?",alice_id,reply)
        audit=sql_one("SELECT outcome FROM admin_audit_logs WHERE resource='feedback' AND target_id=? AND outcome='SUCCESS' ORDER BY id DESC LIMIT 1",str(saved["id"]))
        check("reply updates state and writes notification to correct owner plus success audit",notice==(alice_id,"feedback",reply) and audit is not None and sql_one("SELECT status,admin_reply FROM feedback WHERE id=?",saved["id"])==("RESOLVED",reply))
        count_once=sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback' AND body=?",alice_id,reply)[0]
        admin.call("PUT",f"/api/admin/feedback/{saved['id']}",{"status":"RESOLVED","admin_reply":reply})
        count_twice=sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback' AND body=?",alice_id,reply)[0]
        check("repeating identical reply does not duplicate notification",count_once==1 and count_twice==1 and before_notices+1==sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback'",alice_id)[0])
        admin.call("PUT",f"/api/admin/feedback/{saved['id']}",{"status":"RESOLVED","admin_reply":""},status=400)
        check("resolve requires a nonempty reply",True)

        # Force an audit failure after the reply trigger and verify the API transaction rolls back state and notice.
        trigger="qa_feedback_audit_abort_"+STAMP
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute(f'''CREATE TRIGGER "{trigger}" BEFORE INSERT ON admin_audit_logs
                WHEN NEW.resource='feedback' AND NEW.outcome='SUCCESS'
                BEGIN SELECT RAISE(ABORT,'QA injected feedback audit failure'); END''')
            conn.commit()
        installed_triggers.append(trigger)
        before_fail=sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback'",alice_id)[0]
        failure=admin.raw("PUT",f"/api/admin/feedback/{saved['id']}",{"status":"PENDING","admin_reply":MARK+" must rollback"})
        after_fail=sql_one("SELECT status,admin_reply FROM feedback WHERE id=?",saved["id"])
        notices_after_fail=sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback'",alice_id)[0]
        failed_audit=sql_one("SELECT outcome FROM admin_audit_logs WHERE resource='feedback' AND target_id=? AND outcome='FAILED' ORDER BY id DESC LIMIT 1",str(saved["id"]))
        check("audit failure rolls back feedback state and trigger notice while failed audit may remain",failure.status_code>=400 and after_fail==("RESOLVED",reply) and notices_after_fail==before_fail and failed_audit is not None)
        with sqlite3.connect(DB,timeout=10) as conn:
            conn.execute(f'DROP TRIGGER IF EXISTS "{trigger}"');conn.commit()
        installed_triggers.remove(trigger)
        check("reply notification is scoped to the feedback owner",sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='feedback' AND body=?",int(sql_one("SELECT id FROM users WHERE username LIKE ?",f"qaf_{STAMP}_bob")[0]),reply)[0]==0)
    except Exception as exc:
        OUT.parent.mkdir(parents=True,exist_ok=True)
        OUT.write_text(json.dumps({"target":BASE,"checks_passed":len(checks),"checks":checks,"failure":f"{type(exc).__name__}: {exc}"},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        raise
    finally:
        cleanup()
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps({"target":BASE,"checks_passed":len(checks),"checks":checks},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"target":BASE,"checks_passed":len(checks),"report":str(OUT)},ensure_ascii=False))


def cleanup():
    with sqlite3.connect(DB,timeout=10) as conn:
        conn.execute("PRAGMA foreign_keys=ON")
        for trigger in reversed(installed_triggers):
            conn.execute(f'DROP TRIGGER IF EXISTS "{trigger}"')
        conn.execute("DELETE FROM notifications WHERE user_id IN (%s) AND kind='feedback' AND body LIKE ?" % (",".join("?" for _ in user_ids) or "NULL"), (*user_ids,MARK+"%"))
        for fid in fixture_ids["feedback"]:
            conn.execute("DELETE FROM admin_audit_logs WHERE resource='feedback' AND target_id=?",(str(fid),))
            conn.execute("DELETE FROM feedback WHERE id=?",(fid,))
        for uid in user_ids:
            conn.execute("DELETE FROM admin_audit_logs WHERE resource='feedback' AND target_id IN (SELECT CAST(id AS TEXT) FROM feedback WHERE user_id=? AND body LIKE ?)",(uid,MARK+"%"))
            conn.execute("DELETE FROM feedback WHERE user_id=? AND body LIKE ?",(uid,MARK+"%"))
        for ident in fixture_ids["routes"]: conn.execute("DELETE FROM shared_routes WHERE id=?",(ident,))
        for ident in fixture_ids["trips"]: conn.execute("DELETE FROM trips WHERE id=?",(ident,))
        for ident in fixture_ids["social_comments"]: conn.execute("DELETE FROM social_comments WHERE id=?",(ident,))
        for ident in fixture_ids["posts"]: conn.execute("DELETE FROM social_posts WHERE id=?",(ident,))
        for ident in fixture_ids["foods"]: conn.execute("DELETE FROM foods WHERE id=?",(ident,))
        for ident in fixture_ids["comments"]: conn.execute("DELETE FROM comments WHERE id=?",(ident,))
        for ident in fixture_ids["guides"]: conn.execute("DELETE FROM guides WHERE id=?",(ident,))
        for ident in fixture_ids["spots"]: conn.execute("DELETE FROM spots WHERE id=?",(ident,))
        for kind,ids in (("guide",fixture_ids["guides"]),("spot",fixture_ids["spots"])):
            for ident in ids:
                conn.execute("DELETE FROM search_dirty WHERE type=? AND item_id=?",(kind,ident))
                conn.execute("DELETE FROM search_fts WHERE type=? AND item_id=?",(kind,str(ident)))
        for cid in reversed(fixture_ids["cities"]): conn.execute("DELETE FROM cities WHERE id=?",(cid,))
        for uid in reversed(user_ids): conn.execute("DELETE FROM users WHERE id=?",(uid,))
        conn.commit()


atexit.register(cleanup)


if __name__ == "__main__":
    main()
