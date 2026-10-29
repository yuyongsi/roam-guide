"""Read-only submission preview and public publication-metadata checks on an isolated QA DB."""
from __future__ import annotations

import base64
import json
import os
import sqlite3
import sys
import time
from pathlib import Path
from urllib.parse import urlencode

from http_client import requests

ROOT = Path(__file__).resolve().parents[1]


def isolated_env():
    base = os.environ.get("ROAM_BASE", "").rstrip("/")
    db_s, data_s = os.environ.get("ROAM_QA_DB", ""), os.environ.get("ROAM_DATA_DIR", "")
    if not base or not db_s or not data_s:
        raise SystemExit("拒绝运行：必须显式设置 ROAM_BASE、ROAM_QA_DB 和 ROAM_DATA_DIR。")
    from urllib.parse import urlsplit
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
    return base, db, data


BASE, DB, DATA = isolated_env()
STAMP = str(time.time_ns())[-9:]
checks: list[str] = []
users: list[int] = []
contributions: list[int] = []
canonical: list[tuple[str, int]] = []
images: list[str] = []
public_images: list[str] = []
triggers: list[str] = []
qa_cities: list[int] = []


def check(name: str, condition: bool):
    if not condition:
        raise AssertionError(name)
    checks.append(name)


def sql_one(sql: str, *args):
    with sqlite3.connect(DB, timeout=10) as db:
        return db.execute(sql, args).fetchone()


def sql_all(sql: str, *args):
    with sqlite3.connect(DB, timeout=10) as db:
        return db.execute(sql, args).fetchall()


class Client:
    def __init__(self):
        self.s = requests.Session()
        self.csrf = self.s.get(BASE + "/api/session").json()["csrf"]

    def raw(self, method, path, body=None, *, files=None, csrf=True):
        headers = {"X-CSRF-Token": self.csrf} if csrf else {}
        return self.s.request(method, BASE + path, json=body, files=files, headers=headers)

    def call(self, method, path, body=None, status=200, *, files=None, csrf=True):
        r = self.raw(method, path, body, files=files, csrf=csrf)
        if r.status_code != status:
            raise AssertionError(f"{method} {path}: expected {status}, got {r.status_code}: {r.text[:260]}")
        if not r.content:
            return None
        try:
            out = r.json()
        except Exception:
            return r.content
        if isinstance(out, dict) and out.get("csrf"):
            self.csrf = out["csrf"]
        return out

    def login(self, username, password):
        return self.call("POST", "/api/auth/login", {"username": username, "password": password})


def register(c: Client, tag: str):
    # Keep generated usernames within the application's 24-character limit.
    name, pw = f"qap_{STAMP}_{tag}", "QaPreview!2026"
    user = c.call("POST", "/api/auth/register", {"username": name, "password": pw})["user"]
    users.append(int(user["id"]))
    return int(user["id"]), name, pw


def new_contribution(c: Client, kind: str, operation="CREATE", target=None):
    body = {"kind": kind, "operation": operation}
    if target is not None:
        body["target_id"] = int(target)
    item = c.call("POST", "/api/me/contributions", body)
    contributions.append(int(item["id"]))
    return item


def edit(c: Client, item, payload):
    return c.call("PUT", f"/api/me/contributions/{item['id']}", {"revision": item["revision"], "payload": payload})


def submit(c: Client, item):
    return c.call("POST", f"/api/me/contributions/{item['id']}/submit", {"revision": item["revision"]})


def preview(c: Client, item, revision=None, status=200):
    body = {"revision": item["revision"] if revision is None else revision}
    return c.call("POST", f"/api/me/contributions/{item['id']}/preview", body, status=status)


def review(admin, item, decision, note="", status=200):
    return admin.call("POST", f"/api/admin/contributions/{item['id']}/review",
                      {"revision": item["revision"], "decision": decision, "note": note}, status=status)


def spot_payload(title, city_id, image="/assets/nanyang-hero.jpg", gallery=None):
    return {"city_id": city_id, "title": title, "intro": "QA预览简介", "description": "QA预览正文" + STAMP,
            "highlights": "QA看点", "transportation": "QA交通", "tips": "QA提示", "image": image,
            "source_url": "", "theme": "人文历史", "duration_minutes": 120, "map_query": title,
            "gallery": list(gallery or [])}


def guide_payload(title, city_id, spot_ids):
    return {"city_id": city_id, "title": title, "subtitle": "QA副标题", "intro": "QA攻略简介",
            "image": "/assets/nanyang-hero.jpg", "source_url": "", "theme": "人文历史", "duration_minutes": 180,
            "sections": [{"title": "QA小节一", "body": "QA第一段" + STAMP}, {"title": "QA小节二", "body": "QA第二段" + STAMP}],
            "spot_ids": list(spot_ids)}


def food_payload(title, city_id):
    return {"city_id": city_id, "title": title, "category": "小吃", "intro": "QA美食简介", "description": "QA美食正文" + STAMP,
            "taste": "清香", "tips": "QA提示", "image": "", "source_url": ""}


def upload(c, name="qa-preview.png"):
    raw = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=")
    out = c.call("POST", "/api/me/contribution-images", files={"file": (name, __import__("io").BytesIO(raw), "image/png")})
    key = out["url"].rsplit("/", 1)[-1]
    images.append(key)
    return out["url"]


def search_spots(c, city_id):
    path = "/api/search?" + urlencode({"type": "spot", "city": city_id, "q": "", "size": 24})
    return c.call("GET", path)["items"]


def detail_path(kind, ident):
    return {"spot": f"/api/spots/{ident}", "guide": f"/api/guides/{ident}", "food": f"/api/foods/{ident}"}[kind]


def admin_edit_spot(admin, row, *, published=None, marker="QA管理员编辑"):
    fields = ("city_id", "title", "intro", "description", "highlights", "transportation", "tips", "image", "source_url", "theme", "duration_minutes", "map_query", "gallery")
    body = {k: row[k] for k in fields if k in row}
    body.update({"revision": row["revision"], "published": bool(row["published"]) if published is None else published,
                 "description": str(row["description"]) + marker + STAMP})
    admin.call("PUT", f"/api/admin/spots/{row['id']}", body)


def published_meta(c, kind, ident):
    return c.call("GET", detail_path(kind, ident))["publication"]


def db_snapshot():
    names = ("contributions", "contribution_reviews", "notifications", "admin_audit_logs", "contribution_images",
             "search_dirty", "spots", "guides", "foods", "guide_spots", "schema_migrations")
    out = {}
    with sqlite3.connect(DB, timeout=10) as db:
        for name in names:
            exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
            if exists:
                out[name] = db.execute(f'SELECT * FROM "{name}" ORDER BY rowid').fetchall()
    for folder in (DATA / "uploads", DATA / "contribution-uploads"):
        out[str(folder)] = sorted((p.name, p.stat().st_size, p.stat().st_mtime_ns) for p in folder.glob("*") if p.is_file()) if folder.exists() else []
    return out


def publication_keys_ok(meta):
    allowed = {"author_name", "author_kind", "recorded_at", "published_at", "updated_at", "last_contributor_name", "last_update_kind", "has_updates", "preview_pending"}
    return isinstance(meta, dict) and set(meta).issubset(allowed) and {"author_name", "author_kind", "recorded_at", "published_at", "updated_at", "last_contributor_name", "last_update_kind", "has_updates"}.issubset(meta)


def main():
    admin = Client(); admin.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    alice_id, alice_name, alice_pw = register(Client(), "alice")
    bob_id, bob_name, bob_pw = register(Client(), "bob")
    alice = Client(); alice.login(alice_name, alice_pw)
    bob = Client(); bob.login(bob_name, bob_pw)
    guest = Client()
    cities = guest.call("GET", "/api/cities")
    city = next((x for x in cities if int(x["id"]) == 3), cities[0])
    city_id = int(city["id"])
    public_spots = search_spots(guest, city_id)
    check("preview QA city and same-city public reference spot exist", bool(cities) and bool(public_spots))
    ref_spots = [int(x["id"]) for x in public_spots[:2]]

    # Owner-only preview access, CSRF, input revision and forced account state.
    incomplete = new_contribution(alice, "spot")
    guest.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": incomplete["revision"]}, status=401)
    bob.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": incomplete["revision"]}, status=404)
    admin.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": incomplete["revision"]}, status=404)
    no_csrf = alice.raw("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": incomplete["revision"]}, csrf=False)
    check("preview is owner-only and enforces CSRF", no_csrf.status_code == 403)
    alice.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {}, status=400)
    alice.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": 1.5}, status=400)
    incomplete_preview = preview(alice, incomplete)
    check("incomplete draft preview returns readable item but cannot submit", incomplete_preview["can_submit"] is False and isinstance(incomplete_preview["validation_message"], str) and isinstance(incomplete_preview["warnings"], list))
    check("preview returns merged canonical context and item identity", incomplete_preview["contribution_id"] == incomplete["id"] and incomplete_preview["kind"] == "spot" and "city_name" in incomplete_preview["item"])
    alice.call("PUT", f"/api/me/contributions/{incomplete['id']}", {"revision": incomplete["revision"], "payload": {"author_name": "fake", "recorded_at": "2000-01-01", "published_at": "2000-01-01", "updated_at": "2000-01-01", "publication": {}}}, status=400)

    # Read-only invariant: preview repetition cannot persist data, search queues or image materialization.
    private_url = upload(alice)
    private_marker = private_url
    pspot = spot_payload("QA预览完整景点" + STAMP, city_id, private_url, ["/assets/nanyang-hero.jpg"])
    incomplete = edit(alice, incomplete, pspot)
    before = db_snapshot()
    draft_preview_1 = preview(alice, incomplete)
    draft_preview_2 = preview(alice, incomplete)
    after = db_snapshot()
    check("repeated preview is read-only across SQLite rows, search queue and upload files", before == after)
    check("complete CREATE preview allows submission and keeps private image in preview only",
          draft_preview_1["can_submit"] is True and draft_preview_1["revision"] == incomplete["revision"]
          and draft_preview_1["item"]["image"] == private_url and draft_preview_1["publication"]["preview_pending"] is True
          and draft_preview_1["publication"]["author_name"] == alice_name and draft_preview_1["publication"]["published_at"] is None)
    check("preview warning entries are plain strings", all(isinstance(x, str) for x in draft_preview_1["warnings"]))
    alice.call("POST", f"/api/me/contributions/{incomplete['id']}/preview", {"revision": incomplete["revision"] - 1}, status=409)
    stale_save = edit(alice, incomplete, dict(pspot, intro="自动保存后的另一版本"))
    alice.call("POST", f"/api/me/contributions/{incomplete['id']}/submit", {"revision": incomplete["revision"]}, status=409)
    check("edit in another tab makes an old preview revision unsubmitable", stale_save["revision"] == incomplete["revision"] + 1)
    incomplete = preview(alice, stale_save)["contribution_id"] and alice.call("GET", f"/api/me/contributions/{stale_save['id']}")
    pspot = incomplete["payload"]
    pspot["title"] = "QA预览完整景点" + STAMP
    # Invalid metadata injection is rejected at draft save, but valid canonical fields remain editable.
    incomplete = edit(alice, incomplete, pspot)
    incomplete = submit(alice, incomplete)
    pending_preview = preview(alice, incomplete)
    check("PENDING preview is still readable but cannot submit", pending_preview["status"] == "PENDING" and pending_preview["can_submit"] is False)

    private_get = alice.raw("GET", private_url)
    bob_get = bob.raw("GET", private_url)
    admin_get = admin.raw("GET", private_url)
    guest_get = guest.raw("GET", private_url)
    check("preview private image remains owner/admin-only and unmaterialized", private_get.status_code == 200 and bob_get.status_code == 404 and guest_get.status_code == 401 and admin_get.status_code == 200)
    before_public_files = {p.name for p in (DATA / "uploads").glob("*") if p.is_file()}
    created = review(admin, incomplete, "APPROVED")
    canonical.append(("spots", int(created["published_id"])))
    public_id = int(created["published_id"])
    published_source = sql_one("SELECT image FROM spots WHERE id=?", public_id)[0]
    if published_source.startswith("/uploads/"):
        public_images.append(published_source.rsplit("/", 1)[-1])
    source_review = sql_one("SELECT reviewed_at FROM contributions WHERE id=?", created["id"])[0]
    created_at = sql_one("SELECT created_at FROM spots WHERE id=?", public_id)[0]
    public_detail = alice.call("GET", f"/api/spots/{public_id}")
    public_meta = public_detail["publication"]
    check("first approved CREATE supplies public author and exact publication timestamps",
          public_meta["author_name"] == alice_name and public_meta["author_kind"] == "user"
          and public_meta["recorded_at"] == created_at and public_meta["published_at"] == source_review
          and public_meta["updated_at"] is None and public_meta["last_contributor_name"] is None
          and public_meta["has_updates"] is False)
    check("public publication object is a metadata whitelist without private marker or payload", publication_keys_ok(public_meta)
          and private_marker not in json.dumps(public_detail, ensure_ascii=False)
          and not {"owner_id", "review_note", "payload", "id"}.intersection(public_meta))
    published_image = public_detail["image"]
    check("preview did not publish image early and approval materializes it once", before_public_files <= {p.name for p in (DATA / "uploads").glob("*") if p.is_file()} and published_image.startswith("/uploads/"))
    approved_preview = preview(alice, created)
    check("APPROVED contribution remains previewable but cannot be submitted", approved_preview["status"] == "APPROVED" and approved_preview["can_submit"] is False)

    # UPDATE preview merges the original canonical fields and ordered relationships without persisting.
    update = new_contribution(bob, "spot", "UPDATE", public_id)
    proposal = {"intro": "QA另一位作者的建议" + STAMP}
    update = edit(bob, update, proposal)
    update_preview = preview(bob, update)
    check("UPDATE preview merges original cover, gallery and all unchanged canonical fields",
          update_preview["can_submit"] is True and update_preview["item"]["intro"] == proposal["intro"]
          and update_preview["item"]["title"] == public_detail["title"]
          and update_preview["item"]["image"] == public_detail["image"]
          and update_preview["item"]["gallery"] == public_detail["gallery"]
          and update_preview["publication"]["author_name"] == alice_name
          and update_preview["publication"]["last_contributor_name"] == bob_name
          and update_preview["publication"]["updated_at"] is None
          and update_preview["publication"]["preview_pending"] is True)
    update = submit(bob, update)
    meta_before_update = published_meta(alice, "spot", public_id)
    pending_update_preview = preview(bob, update)
    check("pending UPDATE preview does not change public author or timestamps",
          pending_update_preview["can_submit"] is False and published_meta(alice, "spot", public_id) == meta_before_update)
    update = review(admin, update, "APPROVED")
    update_reviewed_at = sql_one("SELECT reviewed_at FROM contributions WHERE id=?", update["id"])[0]
    updated_meta = published_meta(alice, "spot", public_id)
    check("approved UPDATE changes last contributor but preserves first author and published time",
          updated_meta["author_name"] == alice_name and updated_meta["author_kind"] == "user"
          and updated_meta["recorded_at"] == created_at and updated_meta["published_at"] == source_review
          and updated_meta["last_contributor_name"] == bob_name and updated_meta["last_update_kind"] == "user"
          and updated_meta["updated_at"] == update_reviewed_at and updated_meta["has_updates"] is True)

    # RETURNED and failed audit/notification transactions cannot alter published metadata.
    stable_meta = published_meta(alice, "spot", public_id)
    returned = new_contribution(bob, "spot", "UPDATE", public_id)
    returned = edit(bob, returned, {"intro": "QA退回不应改变署名" + STAMP}); returned = submit(bob, returned)
    returned = review(admin, returned, "RETURNED", "QA preview returned")
    returned_preview = preview(bob, returned)
    check("valid RETURNED proposal can be previewed and resubmitted", returned_preview["status"] == "RETURNED" and returned_preview["can_submit"] is True)
    check("RETURNED proposal leaves public metadata unchanged", published_meta(alice, "spot", public_id) == stable_meta)
    for failure_table in ("admin_audit_logs", "notifications"):
        failed = new_contribution(bob, "spot", "UPDATE", public_id)
        failed = edit(bob, failed, {"intro": "QA注入故障" + failure_table + STAMP}); failed = submit(bob, failed)
        trigger = "qa_preview_" + failure_table + "_" + STAMP
        triggers.append(trigger)
        with sqlite3.connect(DB) as db:
            if failure_table == "admin_audit_logs":
                db.execute(f"CREATE TRIGGER {trigger} BEFORE INSERT ON admin_audit_logs WHEN NEW.resource='contributions' AND NEW.target_id='{failed['id']}' BEGIN SELECT RAISE(ABORT,'QA preview audit failure'); END")
            else:
                db.execute(f"CREATE TRIGGER {trigger} BEFORE INSERT ON notifications WHEN NEW.user_id={bob_id} AND NEW.kind='CONTRIBUTION_REVIEW' AND NEW.path='/me?tab=contributions&open={failed['id']}' BEGIN SELECT RAISE(ABORT,'QA preview notification failure'); END")
            db.commit()
        before_failed_meta = published_meta(alice, "spot", public_id)
        before_failed_row = sql_one("SELECT revision,intro FROM spots WHERE id=?", public_id)
        response = admin.raw("POST", f"/api/admin/contributions/{failed['id']}/review", {"revision": failed["revision"], "decision": "APPROVED", "note": ""})
        check(f"{failure_table} failure leaves published metadata and pending proposal unchanged",
              response.status_code >= 400 and published_meta(alice, "spot", public_id) == before_failed_meta
              and sql_one("SELECT status FROM contributions WHERE id=?", failed["id"])[0] == "PENDING"
              and sql_one("SELECT revision,intro FROM spots WHERE id=?", public_id) == before_failed_row
              and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", failed["id"])[0] == 0
              and sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND path=?", bob_id, f"/me?tab=contributions&open={failed['id']}")[0] == 0
              and sql_one("SELECT count(*) FROM admin_audit_logs WHERE resource='contributions' AND target_id=? AND outcome='SUCCESS'", str(failed["id"]))[0] == 0)
        with sqlite3.connect(DB) as db:
            db.execute(f"DROP TRIGGER IF EXISTS {trigger}"); db.commit()
        triggers.remove(trigger)

    # A successful admin edit is a later editorial update; an admin account name is never public.
    before_admin_edit = alice.call("GET", f"/api/spots/{public_id}")
    admin_edit_spot(admin, before_admin_edit)
    edit_audit = sql_one("SELECT created_at FROM admin_audit_logs WHERE resource='spots' AND target_id=? AND action='EDIT' AND outcome='SUCCESS' ORDER BY id DESC LIMIT 1", str(public_id))[0]
    after_admin_edit = alice.call("GET", f"/api/spots/{public_id}")
    editorial_meta = after_admin_edit["publication"]
    check("successful admin EDIT becomes latest editorial update without exposing admin username",
          editorial_meta["author_name"] == alice_name and editorial_meta["author_kind"] == "user"
          and editorial_meta["updated_at"] == edit_audit and editorial_meta["last_update_kind"] == "editorial"
          and editorial_meta["last_contributor_name"] not in {"admin", ""} and editorial_meta["has_updates"] is True)
    stale_update = new_contribution(bob, "spot", "UPDATE", public_id)
    stale_update = edit(bob, stale_update, {"intro": "QA stale preview" + STAMP})
    before_stale = alice.call("GET", f"/api/spots/{public_id}")
    admin_edit_spot(admin, before_stale, marker="QA造成旧预览版本")
    stale_target_preview = preview(bob, stale_update)
    bob.call("POST", f"/api/me/contributions/{stale_update['id']}/submit", {"revision": stale_update["revision"]}, status=409)
    check("stale UPDATE target remains readable but cannot submit", stale_target_preview["can_submit"] is False and stale_target_preview["warnings"] and stale_target_preview["validation_message"] and stale_target_preview["item"]["intro"] == "QA stale preview" + STAMP)

    # Other kinds publish the same metadata contract; editor-created legacy content falls back cleanly.
    guide = new_contribution(alice, "guide")
    guide = edit(alice, guide, guide_payload("QA预览攻略" + STAMP, city_id, ref_spots))
    guide_preview = preview(alice, guide)
    check("guide preview preserves section order and spot relationship order",
          guide_preview["can_submit"] is True and [x["id"] for x in guide_preview["item"]["spots"]] == ref_spots
          and [x["title"] for x in guide_preview["item"]["sections"]] == ["QA小节一", "QA小节二"])
    guide = submit(alice, guide); guide = review(admin, guide, "APPROVED")
    canonical.append(("guides", int(guide["published_id"])))
    guide_detail = alice.call("GET", f"/api/guides/{guide['published_id']}")
    guide_row = sql_one("SELECT created_at FROM guides WHERE id=?", guide["published_id"])[0]
    guide_reviewed = sql_one("SELECT reviewed_at FROM contributions WHERE id=?", guide["id"])[0]
    check("guide public detail has the same author and exact timestamp contract",
          publication_keys_ok(guide_detail["publication"]) and guide_detail["publication"]["author_name"] == alice_name
          and guide_detail["publication"]["recorded_at"] == guide_row and guide_detail["publication"]["published_at"] == guide_reviewed)

    meal = new_contribution(alice, "food")
    meal = edit(alice, meal, food_payload("QA预览美食" + STAMP, city_id))
    meal_preview = preview(alice, meal)
    check("food preview validates a complete draft", meal_preview["kind"] == "food" and meal_preview["can_submit"] is True)
    meal = submit(alice, meal); meal = review(admin, meal, "APPROVED")
    canonical.append(("foods", int(meal["published_id"])))
    meal_detail = alice.call("GET", f"/api/foods/{meal['published_id']}")
    meal_reviewed = sql_one("SELECT reviewed_at FROM contributions WHERE id=?", meal["id"])[0]
    meal_created = sql_one("SELECT created_at FROM foods WHERE id=?", meal["published_id"])[0]
    check("food detail publication metadata uses the first accepted contribution",
          publication_keys_ok(meal_detail["publication"]) and meal_detail["publication"]["author_name"] == alice_name
          and meal_detail["publication"]["recorded_at"] == meal_created and meal_detail["publication"]["published_at"] == meal_reviewed)

    # Editorial fallback for legacy canonical content without a CREATE contribution.
    editorial_title = "QA编辑元信息回退" + STAMP
    editorial = admin.call("POST", "/api/admin/spots", {**spot_payload(editorial_title, city_id), "published": True})
    editorial_id = int(editorial["id"]); canonical.append(("spots", editorial_id))
    editorial_detail = alice.call("GET", f"/api/spots/{editorial_id}")
    fallback = editorial_detail["publication"]
    check("editorial fallback names the editorial team without false publication time",
          publication_keys_ok(fallback) and fallback["author_kind"] == "editorial"
          and fallback["author_name"] == "漫游编辑整理"
          and fallback["published_at"] is None and fallback["updated_at"] is None)

    # Hidden city and source target conflict safely; preserve only our QA fixtures.
    hidden_city_slug = "qa-preview-hidden-" + STAMP
    with sqlite3.connect(DB) as db:
        hidden_city_id = db.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',0,?,?,?,?,?,?)",
                                    (hidden_city_slug, "QA隐藏预览城市" + STAMP, "QA", "QA", "QA private city", "/assets/nanyang-hero.jpg")).lastrowid
        db.commit()
    qa_cities.append(int(hidden_city_id))
    hidden_draft = new_contribution(alice, "food")
    hidden_draft = edit(alice, hidden_draft, food_payload("QA隐藏城市预览" + STAMP, int(hidden_city_id)))
    hidden_preview = preview(alice, hidden_draft)
    check("hidden city preview uses a placeholder and cannot submit without revealing its name",
          hidden_preview["can_submit"] is False and hidden_preview["warnings"]
          and hidden_city_slug not in json.dumps(hidden_preview, ensure_ascii=False)
          and ("QA隐藏预览城市" + STAMP) not in json.dumps(hidden_preview, ensure_ascii=False))
    trashed = new_contribution(alice, "food")
    trashed = edit(alice, trashed, food_payload("QA回收站预览" + STAMP, city_id))
    trashed = alice.call("POST", f"/api/me/contributions/{trashed['id']}/trash", {"revision": trashed["revision"]})
    trash_preview = preview(alice, trashed)
    check("trashed contribution remains readable but cannot submit", trash_preview["deleted_at"] is not None and trash_preview["can_submit"] is False)

    # Hidden guide association yields warning placeholder, while UPDATE target/city changes are 409.
    qa_related = admin.call("POST", "/api/admin/spots", {**spot_payload("QA预览关联景点" + STAMP, city_id), "published": True})
    qa_related_id = int(qa_related["id"]); canonical.append(("spots", qa_related_id))
    guide_target = new_contribution(alice, "guide")
    guide_target = edit(alice, guide_target, guide_payload("QA预览关联攻略" + STAMP, city_id, [qa_related_id]))
    guide_target = submit(alice, guide_target); guide_target = review(admin, guide_target, "APPROVED")
    canonical.append(("guides", int(guide_target["published_id"])))
    guide_update = new_contribution(bob, "guide", "UPDATE", guide_target["published_id"])
    guide_update = edit(bob, guide_update, {"sections": [{"title": "QA新正文", "body": "保留顺序"}], "spot_ids": [qa_related_id]})
    guide_update_preview = preview(bob, guide_update)
    check("guide UPDATE preview merges ordered sections and association", guide_update_preview["can_submit"] is True
          and guide_update_preview["item"]["sections"][0]["title"] == "QA新正文"
          and [x["id"] for x in guide_update_preview["item"]["spots"]] == [qa_related_id])
    related_row = alice.call("GET", f"/api/spots/{qa_related_id}")
    admin_edit_spot(admin, related_row, published=False, marker="QA下架关联景点")
    guide_preview_hidden = preview(bob, guide_update)
    check("guide preview warns and redacts a hidden associated spot",
          guide_preview_hidden["can_submit"] is False and guide_preview_hidden["warnings"]
          and all(x.get("title") != "QA预览关联景点" + STAMP for x in guide_preview_hidden["item"]["spots"]))
    related_hidden_row = admin.call("GET", "/api/admin/spots")
    related_current = next(x for x in related_hidden_row if int(x["id"]) == qa_related_id)
    admin_edit_spot(admin, related_current, published=True, marker="QA恢复关联景点")

    update_city_slug = "qa-preview-update-city-" + STAMP
    with sqlite3.connect(DB) as db:
        update_city_id = db.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',1,?,?,?,?,?,?)",
                                    (update_city_slug, "QA UPDATE地区" + STAMP, "QA", "QA", "QA update city", "/assets/nanyang-hero.jpg")).lastrowid
        db.commit()
    qa_cities.append(int(update_city_id))
    city_target = new_contribution(alice, "spot")
    city_target = edit(alice, city_target, spot_payload("QA城市隐藏源" + STAMP, int(update_city_id)))
    city_target = submit(alice, city_target); city_target = review(admin, city_target, "APPROVED")
    canonical.append(("spots", int(city_target["published_id"])))
    city_change_draft = new_contribution(bob, "spot", "UPDATE", city_target["published_id"])
    with sqlite3.connect(DB) as db:
        db.execute("UPDATE cities SET published=0,revision=revision+1 WHERE id=?", (int(update_city_id),)); db.commit()
    hidden_city_update_preview = preview(bob, city_change_draft)
    bob.call("POST", f"/api/me/contributions/{city_change_draft['id']}/submit", {"revision": city_change_draft["revision"]}, status=409)
    with sqlite3.connect(DB) as db:
        db.execute("UPDATE cities SET published=1,revision=revision+1 WHERE id=?", (int(update_city_id),)); db.commit()
    check("UPDATE preview reads but blocks a source whose city became unpublished",
          hidden_city_update_preview["can_submit"] is False and hidden_city_update_preview["warnings"] and hidden_city_update_preview["validation_message"]
          and sql_one("SELECT published FROM cities WHERE id=?", int(update_city_id))[0] == 1)

    changed_city = new_contribution(bob, "spot", "UPDATE", public_id)
    other_city = next((x for x in cities if int(x["id"]) != city_id), None)
    if other_city:
        changed_city = edit(bob, changed_city, {"city_id": int(other_city["id"])})
        city_mismatch_preview = preview(bob, changed_city)
        bob.call("POST", f"/api/me/contributions/{changed_city['id']}/submit", {"revision": changed_city["revision"]}, status=409)
        check("UPDATE preview explains city mismatch and submit rejects it", city_mismatch_preview["can_submit"] is False and city_mismatch_preview["warnings"] and city_mismatch_preview["validation_message"])
    target_hidden = new_contribution(bob, "spot", "UPDATE", public_id)
    current_target = alice.call("GET", f"/api/spots/{public_id}")
    admin_edit_spot(admin, current_target, published=False, marker="QA下架UPDATE目标")
    hidden_target_preview = preview(bob, target_hidden)
    bob.call("POST", f"/api/me/contributions/{target_hidden['id']}/submit", {"revision": target_hidden["revision"]}, status=409)
    check("UPDATE preview explains a hidden target and submit rejects it", hidden_target_preview["can_submit"] is False
          and hidden_target_preview["warnings"] and hidden_target_preview["validation_message"]
          and sql_one("SELECT published FROM spots WHERE id=?", public_id)[0] == 0)
    current_admin_rows = admin.call("GET", "/api/admin/spots")
    current_target = next(x for x in current_admin_rows if int(x["id"]) == public_id)
    admin_edit_spot(admin, current_target, published=True, marker="QA恢复UPDATE目标")

    # Forced-password and banned users may not preview until restored, using only a QA account.
    forced_draft = new_contribution(bob, "food")
    forced_draft = edit(bob, forced_draft, food_payload("QA强制改密预览" + STAMP, city_id))
    reset = admin.call("POST", f"/api/admin/users/{bob_id}/reset-password")
    bob = Client(); bob.login(bob_name, reset["temporary_password"])
    bob.call("POST", f"/api/me/contributions/{forced_draft['id']}/preview", {"revision": forced_draft["revision"]}, status=409)
    bob.call("POST", "/api/user/password", {"old_password": reset["temporary_password"], "new_password": "QaPreview!Changed2026"})
    check("forced-password account preview resumes after password change", preview(bob, forced_draft)["can_submit"] is True)
    admin.call("POST", f"/api/admin/users/{bob_id}/status", {"status": "BANNED"})
    banned_preview = bob.raw("POST", f"/api/me/contributions/{forced_draft['id']}/preview", {"revision": forced_draft["revision"]})
    # Invalidated sessions may be rejected at CSRF middleware (403) before auth (401).
    check("banned account cannot use preview", banned_preview.status_code in {401, 403})
    admin.call("POST", f"/api/admin/users/{bob_id}/status", {"status": "ACTIVE"})

    # Only the public detail endpoints reveal publication metadata, and hidden detail remains 404.
    admin_public = alice.call("GET", f"/api/spots/{editorial_id}")
    hidden_admin_rows = admin.call("GET", "/api/admin/spots")
    hidden_target_row = next(x for x in hidden_admin_rows if int(x["id"]) == editorial_id)
    admin_edit_spot(admin, hidden_target_row, published=False, marker="QA最终隐藏")
    hidden_response = alice.raw("GET", f"/api/spots/{editorial_id}")
    check("unpublished QA detail is still protected with 404", hidden_response.status_code == 404)

    # No preview operation should create audit/review rows or mutate search state unexpectedly.
    check("preview fixtures and current database remain referentially sound",
          sql_one("PRAGMA integrity_check")[0] == "ok" and not sql_all("PRAGMA foreign_key_check"))


failure = None
try:
    main()
except BaseException as exc:
    failure = exc
finally:
    with sqlite3.connect(DB, timeout=10) as db:
        db.execute("PRAGMA foreign_keys=ON")
        for trigger in triggers:
            db.execute(f"DROP TRIGGER IF EXISTS {trigger}")
        if users:
            marks = ",".join("?" for _ in users)
            db.execute(f"DELETE FROM notifications WHERE user_id IN ({marks})", users)
        for cid in contributions:
            db.execute("DELETE FROM contributions WHERE id=?", (cid,))
            db.execute("DELETE FROM admin_audit_logs WHERE resource='contributions' AND target_id=?", (str(cid),))
        for table, ident in reversed(canonical):
            db.execute("DELETE FROM admin_audit_logs WHERE resource=? AND target_id=?", (table, str(ident)))
            if table == "guides": db.execute("DELETE FROM guide_spots WHERE guide_id=?", (ident,))
            db.execute(f"DELETE FROM {table} WHERE id=?", (ident,))
        if users:
            marks = ",".join("?" for _ in users)
            db.execute(f"DELETE FROM contribution_images WHERE owner_id IN ({marks})", users)
            db.execute(f"DELETE FROM admin_audit_logs WHERE resource='users' AND target_id IN ({','.join('?' for _ in users)})", [str(x) for x in users])
            db.execute(f"DELETE FROM users WHERE id IN ({marks})", users)
        for cid in qa_cities: db.execute("DELETE FROM cities WHERE id=?", (cid,))
        db.commit()
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    for key in images: (DATA / "contribution-uploads" / key).unlink(missing_ok=True)
    for filename in public_images: (DATA / "uploads" / filename).unlink(missing_ok=True)

if checks:
    target = ROOT / "target"; target.mkdir(exist_ok=True)
    (target / "contribution-preview-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
if failure is not None:
    print("FAIL after", len(checks), "contribution preview checks:", failure, file=sys.stderr)
    raise failure
print("PASS", len(checks), "isolated contribution preview/public-metadata checks")
