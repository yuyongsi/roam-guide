"""Isolated HTTP/SQLite checks for contribution notifications, trash, images and duplicate hints."""
from __future__ import annotations

import base64
import json
import os
import sqlite3
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier
from urllib.parse import urlencode

from http_client import requests

ROOT = Path(__file__).resolve().parents[1]


def isolated_env():
    base = os.environ.get("ROAM_BASE", "").rstrip("/")
    db_s, data_s = os.environ.get("ROAM_QA_DB", ""), os.environ.get("ROAM_DATA_DIR", "")
    if not base or not db_s or not data_s:
        raise SystemExit("拒绝运行：必须显式设置 ROAM_BASE、ROAM_QA_DB 和 ROAM_DATA_DIR。")
    from urllib.parse import urlsplit
    url = urlsplit(base)
    if url.scheme != "http" or url.hostname not in {"127.0.0.1", "localhost"} or not url.port or url.port == 8818:
        raise SystemExit("拒绝运行：仅接受本机非 8818 HTTP 服务。")
    db, data = Path(db_s).resolve(), Path(data_s).resolve()
    work = (ROOT / "work").resolve()
    if db == (ROOT / "data" / "guide.db").resolve() or data == (ROOT / "data").resolve():
        raise SystemExit("拒绝运行：不可访问项目正式数据库。")
    try:
        db.relative_to(work); data.relative_to(work)
    except ValueError:
        raise SystemExit("拒绝运行：隔离数据库与媒体目录必须位于项目 work/ 下。")
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
cities: list[int] = []


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
        self.session = requests.Session()
        self.csrf = self.session.get(BASE + "/api/session").json()["csrf"]

    def raw(self, method: str, path: str, body=None, files=None, csrf=True):
        headers = {"X-CSRF-Token": self.csrf} if csrf else {}
        return self.session.request(method, BASE + path, json=body, files=files, headers=headers)

    def call(self, method: str, path: str, body=None, status=200, files=None, csrf=True):
        r = self.raw(method, path, body, files, csrf)
        if r.status_code != status:
            raise AssertionError(f"{method} {path}: expected {status}, got {r.status_code}: {r.text[:240]}")
        if not r.content:
            return None
        try:
            d = r.json()
        except Exception:
            return r.content
        if isinstance(d, dict) and d.get("csrf"):
            self.csrf = d["csrf"]
        return d

    def login(self, username, password):
        return self.call("POST", "/api/auth/login", {"username": username, "password": password})


def register(c: Client, tag: str):
    name, password = f"qa_life_{STAMP}_{tag}", "QaLifecycle!2026"
    user = c.call("POST", "/api/auth/register", {"username": name, "password": password})["user"]
    users.append(int(user["id"]))
    return int(user["id"]), name, password


def make_contribution(c: Client, kind="food", operation="CREATE", target_id=None):
    body = {"kind": kind, "operation": operation}
    if target_id is not None:
        body["target_id"] = int(target_id)
    item = c.call("POST", "/api/me/contributions", body)
    contributions.append(int(item["id"]))
    return item


def edit(c: Client, item: dict, payload: dict):
    return c.call("PUT", f"/api/me/contributions/{item['id']}", {"revision": item["revision"], "payload": payload})


def submit(c: Client, item: dict):
    return c.call("POST", f"/api/me/contributions/{item['id']}/submit", {"revision": item["revision"]})


def review(admin: Client, item: dict, choice: str, note="", status=200):
    return admin.call("POST", f"/api/admin/contributions/{item['id']}/review",
                      {"revision": item["revision"], "decision": choice, "note": note}, status=status)


def food(title: str, city_id: int):
    return {"city_id": city_id, "title": title, "category": "小吃", "intro": "QA生命周期简介",
            "description": "QA生命周期正文", "taste": "清爽", "tips": "QA提示", "image": "", "source_url": ""}


def spot(title: str, city_id: int, image="/assets/nanyang-hero.jpg", published=True):
    return {"city_id": city_id, "title": title, "intro": "QA lifecycle intro", "description": "QA lifecycle description",
            "highlights": "QA highlight", "transportation": "QA transit", "tips": "QA tips", "image": image,
            "source_url": "", "theme": "人文历史", "duration_minutes": 120, "map_query": title, "gallery": [], "published": published}


def contribution_spot(title: str, city_id: int, image="/assets/nanyang-hero.jpg"):
    payload = spot(title, city_id, image)
    payload.pop("published")
    return payload


def upload(c: Client, name="qa.png") -> str:
    raw = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=")
    result = c.call("POST", "/api/me/contribution-images", files={"file": (name, __import__("io").BytesIO(raw), "image/png")})
    key = result["url"].rsplit("/", 1)[-1]
    images.append(key)
    return result["url"]


def duplicate_query(c: Client, kind: str, city: int, title: str, exclude=0):
    return c.call("GET", "/api/contributions/duplicates?" + urlencode({"kind": kind, "city_id": city, "title": title, "exclude_id": exclude}))


def main():
    admin = Client(); admin.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    aid, aname, apw = register(Client(), "a")
    bid, bname, bpw = register(Client(), "b")
    a = Client(); a.login(aname, apw)
    b = Client(); b.login(bname, bpw)
    guest = Client()
    public_cities = guest.call("GET", "/api/cities")
    check("QA database has published city fixtures", bool(public_cities))
    city = next((x for x in public_cities if int(x["id"]) == 3), public_cities[0])
    city_id, city_name = int(city["id"]), city["name"]
    other_city = next((x for x in public_cities if int(x["id"]) != city_id), None)
    guest.call("GET", "/api/contributions/duplicates?" + urlencode({"kind": "spot", "city_id": city_id, "title": "QA"}), status=401)

    # Review notifications: one per committed RETURNED/APPROVED decision, private and linked.
    returned = make_contribution(a)
    returned = edit(a, returned, food("QA通知退回" + STAMP, city_id)); returned = submit(a, returned)
    ret = review(admin, returned, "RETURNED", "QA lifecycle review")
    ret_id = int(ret["id"])
    own = a.call("GET", "/api/me/notifications")
    notice = next(x for x in own["items"] if x["kind"] == "CONTRIBUTION_REVIEW" and x["path"] == f"/me?tab=contributions&open={ret_id}")
    check("RETURNED decision creates one unread owner notification with contribution deeplink",
          not notice["seen"] and sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='CONTRIBUTION_REVIEW' AND path=?", aid, notice["path"])[0] == 1)
    check("other account cannot see owner's review notification", all(x["id"] != notice["id"] for x in b.call("GET", "/api/me/notifications")["items"]))
    b.call("PUT", "/api/me/notifications/read", {"id": notice["id"]}, status=404)
    a.call("PUT", "/api/me/notifications/read", {"id": notice["id"]})
    check("owner can mark review notification read", sql_one("SELECT seen FROM notifications WHERE id=?", notice["id"])[0] == 1)
    returned = a.call("GET", f"/api/me/contributions/{ret_id}")
    returned = edit(a, returned, dict(returned["payload"], description="已按审核意见补充")); returned = submit(a, returned)
    approved = review(admin, returned, "APPROVED")
    canonical.append(("foods", int(approved["published_id"])))
    review(admin, approved, "APPROVED", status=409)
    own = a.call("GET", "/api/me/notifications")
    owner_notices = [x for x in own["items"] if x["kind"] == "CONTRIBUTION_REVIEW" and x["path"] == f"/me?tab=contributions&open={ret_id}"]
    check("APPROVED decision adds one second notification and replay adds none",
          len(owner_notices) == 2 and sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND kind='CONTRIBUTION_REVIEW' AND path=?", aid, notice["path"])[0] == 2)

    # A review failure at any transactional table leaves status, canonical data, all audit and messages untouched.
    for fail_table in ("notifications", "contribution_reviews", "admin_audit_logs"):
        bad = make_contribution(a)
        bad_title = f"QA通知事务{fail_table}{STAMP}"
        bad = edit(a, bad, food(bad_title, city_id)); bad = submit(a, bad)
        trigger = "qa_lifecycle_" + fail_table + "_" + STAMP
        triggers.append(trigger)
        with sqlite3.connect(DB) as db:
            if fail_table == "notifications":
                sql = f"CREATE TRIGGER {trigger} BEFORE INSERT ON notifications WHEN NEW.user_id={aid} AND NEW.kind='CONTRIBUTION_REVIEW' AND NEW.path='/me?tab=contributions&open={bad['id']}' BEGIN SELECT RAISE(ABORT,'QA notification failure'); END"
            elif fail_table == "contribution_reviews":
                sql = f"CREATE TRIGGER {trigger} BEFORE INSERT ON contribution_reviews WHEN NEW.contribution_id={bad['id']} BEGIN SELECT RAISE(ABORT,'QA review failure'); END"
            else:
                sql = f"CREATE TRIGGER {trigger} BEFORE INSERT ON admin_audit_logs WHEN NEW.resource='contributions' AND NEW.target_id='{bad['id']}' BEGIN SELECT RAISE(ABORT,'QA audit failure'); END"
            db.execute(sql); db.commit()
        failed = admin.raw("POST", f"/api/admin/contributions/{bad['id']}/review", {"revision": bad["revision"], "decision": "APPROVED", "note": ""})
        state = sql_one("SELECT status FROM contributions WHERE id=?", bad["id"])[0]
        check(f"injected {fail_table} failure aborts review and notification transaction",
              failed.status_code >= 400 and state == "PENDING"
              and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", bad["id"])[0] == 0
              and sql_one("SELECT count(*) FROM admin_audit_logs WHERE resource='contributions' AND target_id=?", str(bad["id"]))[0] == 0
              and sql_one("SELECT count(*) FROM notifications WHERE user_id=? AND path=?", aid, f"/me?tab=contributions&open={bad['id']}")[0] == 0
              and sql_one("SELECT count(*) FROM foods WHERE title=?", bad_title)[0] == 0)
        with sqlite3.connect(DB) as db:
            db.execute(f"DROP TRIGGER IF EXISTS {trigger}"); db.commit()
        triggers.remove(trigger)
        a.call("POST", f"/api/me/contributions/{bad['id']}/trash", {"revision": bad["revision"]}, status=409)
        a.call("DELETE", f"/api/me/contributions/{bad['id']}", {"revision": bad["revision"]}, status=409)

    # Recycle bin: revision checks, ownership, admin hiding, restore fidelity and permanent-delete boundary.
    draft = make_contribution(a, "spot")
    draft = edit(a, draft, {"title": "QA回收站内容保留" + STAMP})
    rev1 = draft["revision"]
    draft = edit(a, draft, {"title": "QA回收站内容保留" + STAMP, "intro": "自动保存续写"})
    check("draft autosave revisions increase monotonically", draft["revision"] == rev1 + 1)
    b.call("POST", f"/api/me/contributions/{draft['id']}/trash", {"revision": draft["revision"]}, status=404)
    b.call("POST", f"/api/me/contributions/{draft['id']}/restore", {"revision": draft["revision"]}, status=404)
    no_csrf = a.raw("POST", f"/api/me/contributions/{draft['id']}/trash", {"revision": draft["revision"]}, csrf=False)
    check("trash mutation enforces CSRF", no_csrf.status_code == 403)
    trashed = a.call("POST", f"/api/me/contributions/{draft['id']}/trash", {"revision": draft["revision"]})
    check("trash stamps deleted_at and increments revision", bool(trashed["deleted_at"]) and trashed["revision"] == draft["revision"] + 1)
    active = a.call("GET", "/api/me/contributions?trash=false&size=50")
    recycle = a.call("GET", "/api/me/contributions?trash=true&size=50")
    check("active list and recycle bin separate item and expose trash_count",
          all(x["id"] != draft["id"] and x["deleted_at"] is None for x in active["items"])
          and any(x["id"] == draft["id"] and x["deleted_at"] for x in recycle["items"])
          and active["trash_count"] == recycle["trash_count"] >= 1)
    admin_list = admin.call("GET", "/api/admin/contributions?status=&size=50")
    admin.call("GET", f"/api/admin/contributions/{draft['id']}", status=404)
    check("trashed item is absent from admin listing", all(x["id"] != draft["id"] for x in admin_list["items"]))
    review_trashed = admin.raw("POST", f"/api/admin/contributions/{draft['id']}/review", {"revision": trashed["revision"], "decision": "APPROVED", "note": ""})
    check("admin cannot review a trashed item", review_trashed.status_code in {404, 409})
    a.call("PUT", f"/api/me/contributions/{draft['id']}", {"revision": trashed["revision"], "payload": {"title": "禁止编辑"}}, status=409)
    a.call("POST", f"/api/me/contributions/{draft['id']}/submit", {"revision": trashed["revision"]}, status=409)
    a.call("POST", f"/api/me/contributions/{draft['id']}/restore", {"revision": draft["revision"]}, status=409)
    a.call("DELETE", f"/api/me/contributions/{draft['id']}", {"revision": draft["revision"]}, status=409)
    restored = a.call("POST", f"/api/me/contributions/{draft['id']}/restore", {"revision": trashed["revision"]})
    check("restore preserves draft content and clears deleted_at", restored["deleted_at"] is None and restored["payload"] == draft["payload"])
    a.call("POST", f"/api/me/contributions/{draft['id']}/trash", {"revision": trashed["revision"]}, status=409)
    update_draft = make_contribution(a, "food", "UPDATE", approved["published_id"])
    update_trash = a.call("POST", f"/api/me/contributions/{update_draft['id']}/trash", {"revision": update_draft["revision"]})
    a.call("POST", f"/api/me/contributions/{update_draft['id']}/rebase", {"revision": update_trash["revision"]}, status=409)

    returned_delete = make_contribution(a)
    returned_delete = edit(a, returned_delete, food("QA永久删除退回" + STAMP, city_id)); returned_delete = submit(a, returned_delete)
    returned_delete = review(admin, returned_delete, "RETURNED", "QA remove")
    review_rows_before = sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", returned_delete["id"])[0]
    a.call("DELETE", f"/api/me/contributions/{returned_delete['id']}", {"revision": returned_delete["revision"]}, status=409)
    to_trash = a.call("POST", f"/api/me/contributions/{returned_delete['id']}/trash", {"revision": returned_delete["revision"]})
    a.call("DELETE", f"/api/me/contributions/{returned_delete['id']}", {"revision": to_trash["revision"] - 1}, status=409)
    restored_returned = a.call("POST", f"/api/me/contributions/{returned_delete['id']}/restore", {"revision": to_trash["revision"]})
    check("restore preserves RETURNED status and review history", restored_returned["status"] == "RETURNED" and restored_returned["deleted_at"] is None and len(restored_returned["reviews"]) == 1)
    trashed_again = a.call("POST", f"/api/me/contributions/{returned_delete['id']}/trash", {"revision": restored_returned["revision"]})
    a.call("DELETE", f"/api/me/contributions/{returned_delete['id']}", {"revision": trashed_again["revision"]})
    a.call("GET", f"/api/me/contributions/{returned_delete['id']}", status=404)
    check("permanent deletion is trash-only and cascades review history", review_rows_before == 1 and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", returned_delete["id"])[0] == 0)
    a.call("POST", f"/api/me/contributions/{approved['id']}/trash", {"revision": approved["revision"]}, status=409)
    a.call("DELETE", f"/api/me/contributions/{approved['id']}", {"revision": approved["revision"]}, status=409)

    # Draft capacity applies only to active DRAFT/RETURNED. Trash frees one slot; restore respects the cap.
    quota_id, quota_name, quota_pw = register(Client(), "quota")
    quota = Client(); quota.login(quota_name, quota_pw)
    quota_items = []
    for _ in range(100):
        item = quota.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"})
        contributions.append(int(item["id"])); quota_items.append(item)
    quota.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"}, status=400)
    first_trash = quota.call("POST", f"/api/me/contributions/{quota_items[0]['id']}/trash", {"revision": quota_items[0]["revision"]})
    replacement = quota.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"})
    contributions.append(int(replacement["id"]))
    quota.call("POST", f"/api/me/contributions/{quota_items[0]['id']}/restore", {"revision": first_trash["revision"]}, status=400)
    second_trash = quota.call("POST", f"/api/me/contributions/{quota_items[1]['id']}/trash", {"revision": quota_items[1]["revision"]})
    quota.call("POST", f"/api/me/contributions/{quota_items[0]['id']}/restore", {"revision": first_trash["revision"]})
    quota.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"}, status=400)
    check("quota counts active drafts only and restore cannot exceed 100", sql_one("SELECT count(*) FROM contributions WHERE owner_id=? AND deleted_at IS NULL AND status IN ('DRAFT','RETURNED')", quota_id)[0] == 100)

    quota_images = [upload(quota, f"quota-{i}.png").rsplit("/", 1)[-1] for i in range(100)]
    quota_image_list = quota.call("GET", "/api/me/contribution-images")
    check("image manager caps visible records at 100", quota_image_list["limit"] == 100 and quota_image_list["total"] == 100 and len(quota_image_list["items"]) == 100)
    quota.call("POST", "/api/me/contribution-images", files={"file": ("quota-over.png", __import__("io").BytesIO(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=")), "image/png")}, status=400)
    quota.call("DELETE", f"/api/me/contribution-images/{quota_images[0]}")
    released = upload(quota, "quota-released.png")
    check("deleting an unused image releases one of the 100 upload slots", len(sql_all("SELECT key FROM contribution_images WHERE owner_id=?", quota_id)) == 100 and released.rsplit("/", 1)[-1] in {x[0] for x in sql_all("SELECT key FROM contribution_images WHERE owner_id=?", quota_id)})

    # Image ownership, reference protection, deleting frees quota, and save/delete race consistency.
    free_url = upload(a, "qa-free.png")
    free_key = free_url.rsplit("/", 1)[-1]
    image_list = a.call("GET", "/api/me/contribution-images")
    check("image manager returns bounded item shape and unreferenced status",
          image_list["limit"] == 100 and image_list["total"] >= 1
          and any(x["key"] == free_key and x["url"] == free_url and x["in_use"] is False and x["reference_count"] == 0 for x in image_list["items"]))
    guest.call("GET", "/api/me/contribution-images", status=401)
    other_list = b.call("GET", "/api/me/contribution-images")
    check("private image manager list is isolated by owner", all(x["key"] != free_key for x in other_list["items"]))
    b.call("DELETE", f"/api/me/contribution-images/{free_key}", status=404)
    forged = make_contribution(b, "spot")
    b.call("PUT", f"/api/me/contributions/{forged['id']}", {"revision": forged["revision"], "payload": contribution_spot("QA冒用私图", city_id, free_url)}, status=400)
    registry_trigger = "qa_lifecycle_image_registry_" + STAMP
    triggers.append(registry_trigger)
    with sqlite3.connect(DB) as db:
        db.execute(f"CREATE TRIGGER {registry_trigger} BEFORE DELETE ON contribution_images WHEN OLD.key='{free_key}' BEGIN SELECT RAISE(ABORT,'QA image registry failure'); END")
        db.commit()
    failed_image_delete = a.raw("DELETE", f"/api/me/contribution-images/{free_key}")
    preserved_image = a.raw("GET", free_url)
    quarantine_files = list((DATA / "contribution-uploads").glob(".delete-*"))
    check("registry-delete failure preserves image row and restores original file",
          failed_image_delete.status_code >= 400
          and sql_one("SELECT count(*) FROM contribution_images WHERE key=? AND owner_id=?", free_key, aid)[0] == 1
          and (DATA / "contribution-uploads" / free_key).is_file()
          and preserved_image.status_code == 200 and len(preserved_image.content) > 0
          and not quarantine_files)
    with sqlite3.connect(DB) as db:
        db.execute(f"DROP TRIGGER IF EXISTS {registry_trigger}"); db.commit()
    triggers.remove(registry_trigger)
    a.call("DELETE", f"/api/me/contribution-images/{free_key}")
    a.call("GET", free_url, status=404)
    check("other-owner forged image reference does not prevent legitimate image deletion after recovery", sql_one("SELECT count(*) FROM contribution_images WHERE key=?", free_key)[0] == 0)

    trash_url = upload(a, "qa-trash-ref.png")
    trash_key = trash_url.rsplit("/", 1)[-1]
    img_draft = make_contribution(a, "spot")
    img_draft = edit(a, img_draft, contribution_spot("QA回收站图片引用" + STAMP, city_id, trash_url))
    img_trashed = a.call("POST", f"/api/me/contributions/{img_draft['id']}/trash", {"revision": img_draft["revision"]})
    image_items = a.call("GET", "/api/me/contribution-images")["items"]
    protected = next(x for x in image_items if x["key"] == trash_key)
    check("trash payload remains counted as an image reference", protected["in_use"] and protected["reference_count"] > 0)
    a.call("DELETE", f"/api/me/contribution-images/{trash_key}", status=409)
    a.call("DELETE", f"/api/me/contributions/{img_draft['id']}", {"revision": img_trashed["revision"]})
    a.call("DELETE", f"/api/me/contribution-images/{trash_key}")
    a.call("GET", trash_url, status=404)

    approved_url = upload(a, "qa-approved-ref.png")
    approved_key = approved_url.rsplit("/", 1)[-1]
    image_spot = make_contribution(a, "spot")
    image_spot = edit(a, image_spot, contribution_spot("QA已通过图片保护" + STAMP, city_id, approved_url))
    image_spot = submit(a, image_spot)
    image_spot = review(admin, image_spot, "APPROVED")
    canonical.append(("spots", int(image_spot["published_id"])))
    published_source = sql_one("SELECT image FROM spots WHERE id=?", image_spot["published_id"])[0]
    if published_source.startswith("/uploads/"):
        public_images.append(published_source.rsplit("/", 1)[-1])
    published_image = guest.call("GET", f"/api/spots/{image_spot['published_id']}")["image"]
    a.call("DELETE", f"/api/me/contribution-images/{approved_key}", status=409)
    check("approved contribution history keeps source image protected", any(x["key"] == approved_key and x["in_use"] for x in a.call("GET", "/api/me/contribution-images")["items"]))

    race_url = upload(a, "qa-image-race.png")
    race_key = race_url.rsplit("/", 1)[-1]
    race_draft = make_contribution(a, "spot")
    a1 = Client(); a1.login(aname, apw); a2 = Client(); a2.login(aname, apw)
    gate = Barrier(2)
    def delete_image():
        gate.wait(); return a1.raw("DELETE", f"/api/me/contribution-images/{race_key}")
    def save_image_ref():
        gate.wait(); return a2.raw("PUT", f"/api/me/contributions/{race_draft['id']}",
                                   {"revision": race_draft["revision"], "payload": contribution_spot("QA图片删除保存竞态" + STAMP, city_id, race_url)})
    with ThreadPoolExecutor(max_workers=2) as pool:
        delete_response, save_response = list(pool.map(lambda fn: fn(), [delete_image, save_image_ref]))
    race_saved = a.call("GET", f"/api/me/contributions/{race_draft['id']}")
    race_image_row = sql_one("SELECT count(*) FROM contribution_images WHERE key=? AND owner_id=?", race_key, aid)[0]
    race_file_exists = (DATA / "contribution-uploads" / race_key).is_file()
    image_is_referenced = race_saved["payload"].get("image") == race_url
    check("concurrent image delete/save is serialized without dangling references",
          ((delete_response.status_code == 200 and save_response.status_code == 400 and not image_is_referenced and race_image_row == 0 and not race_file_exists)
           or (delete_response.status_code == 409 and save_response.status_code == 200 and image_is_referenced and race_image_row == 1 and race_file_exists)))

    # Authenticated public duplicate hints: exact normalization, city-prefix similarity, privacy, cap and non-blocking save.
    admin_spots = []
    def admin_create_spot(title, cid, published=True):
        out = admin.call("POST", "/api/admin/spots", spot(title, cid, published=published))
        ident = int(out["id"]); canonical.append(("spots", ident)); admin_spots.append(ident)
        return ident
    exact_title = "ＱＡ 投稿重复规范 " + STAMP
    exact_id = admin_create_spot(exact_title, city_id)
    exact_result = duplicate_query(a, "spot", city_id, "qa投稿重复规范" + STAMP)
    check("duplicate checker NFKC/case/space normalization finds exact public title",
          exact_result["exact"] is True and any(int(x["id"]) == exact_id for x in exact_result["items"]))
    exact_item = next(x for x in exact_result["items"] if int(x["id"]) == exact_id)
    check("duplicate result exposes stable public item fields", {"id", "title", "city_name", "url", "match_type"}.issubset(exact_item))
    excluded = duplicate_query(a, "spot", city_id, "qa投稿重复规范" + STAMP, exact_id)
    check("exclude_id omits selected public result", all(int(x["id"]) != exact_id for x in excluded["items"]))
    prefix_title = city_name + "QA城市前缀重复石刻" + STAMP
    prefix_id = admin_create_spot(prefix_title, city_id)
    similar = duplicate_query(a, "spot", city_id, "QA城市前缀重复石刻" + STAMP)
    check("city-name-prefix candidate is reported as similar but not exact",
          similar["exact"] is False and any(int(x["id"]) == prefix_id and "match_type" in x for x in similar["items"]))
    check("empty duplicate title has stable empty response", duplicate_query(a, "spot", city_id, "").get("items") == [] and duplicate_query(a, "spot", city_id, "").get("exact") is False)
    special = duplicate_query(a, "spot", city_id, "%'_\\ OR 1=1 --")
    check("duplicate special characters are safe and bounded", isinstance(special["items"], list) and len(special["items"]) <= 5)

    cap_ids = [admin_create_spot(f"QA重复提醒上限{STAMP}-{i}", city_id) for i in range(6)]
    capped = duplicate_query(a, "spot", city_id, f"QA重复提醒上限{STAMP}")
    check("duplicate results are capped at five", len(capped["items"]) <= 5)
    check("all capped-result fixtures belong to this QA run", all(i in cap_ids for i in [int(x["id"]) for x in capped["items"]]))
    priority_id = admin_create_spot(f"QA重复提醒上限{STAMP}", city_id)
    prioritized = duplicate_query(a, "spot", city_id, f"QA重复提醒上限{STAMP}")
    check("exact duplicate is ranked before similar matches", prioritized["exact"] is True and prioritized["items"] and int(prioritized["items"][0]["id"]) == priority_id)
    food_duplicate = admin.call("POST", "/api/admin/foods", {"city_id": city_id, "title": exact_title, "category": "小吃", "intro": "QA", "description": "QA", "taste": "", "tips": "", "image": "", "source_url": "", "published": True})
    food_duplicate_id = int(food_duplicate["id"]); canonical.append(("foods", food_duplicate_id))
    spot_only = duplicate_query(a, "spot", city_id, "qa投稿重复规范" + STAMP)
    food_only = duplicate_query(a, "food", city_id, "qa投稿重复规范" + STAMP)
    check("duplicate query scopes candidates to requested kind", all(int(x["id"]) != food_duplicate_id for x in spot_only["items"]) and any(int(x["id"]) == food_duplicate_id for x in food_only["items"]))
    if other_city:
        cross_id = admin_create_spot("QA跨城相同名字" + STAMP, int(other_city["id"]))
        cross = duplicate_query(a, "spot", city_id, "QA跨城相同名字" + STAMP)
        check("duplicate search excludes same title in another city", all(int(x["id"]) != cross_id for x in cross["items"]))

    hidden_city_slug = "qa-lifecycle-hidden-" + STAMP
    with sqlite3.connect(DB) as db:
        hidden_city_id = db.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',0,?,?,?,?,?,?)",
                                    (hidden_city_slug, "QA隐藏城市" + STAMP, "QA", "QA", "QA hidden city", "/assets/nanyang-hero.jpg")).lastrowid
        hidden_spot_id = db.execute("INSERT INTO spots(city_id,title,intro,description,image,published) VALUES(?,?,?,?,?,1)",
                                    (hidden_city_id, "QA隐藏城市重复" + STAMP, "QA", "QA", "/assets/nanyang-hero.jpg")).lastrowid
        hidden_city_spot = db.execute("INSERT INTO spots(city_id,title,intro,description,image,published) VALUES(?,?,?,?,?,0)",
                                      (city_id, "QA下架资料重复" + STAMP, "QA", "QA", "/assets/nanyang-hero.jpg")).lastrowid
        db.commit()
    cities.append(int(hidden_city_id)); canonical.extend([("spots", int(hidden_spot_id)), ("spots", int(hidden_city_spot))])
    hidden_response = a.raw("GET", "/api/contributions/duplicates?" + urlencode({"kind": "spot", "city_id": int(hidden_city_id), "title": "QA隐藏城市重复" + STAMP, "exclude_id": 0}))
    check("duplicate query rejects unpublished city IDs without exposing contents", hidden_response.status_code == 400)
    unpublished_match = duplicate_query(a, "spot", city_id, "QA下架资料重复" + STAMP)
    check("duplicate hints exclude unpublished cities and unpublished canonical content",
          all(int(x["id"]) != hidden_city_spot for x in unpublished_match["items"]))
    private = make_contribution(a, "spot")
    private_title = "QA仅草稿不可见" + STAMP
    private = edit(a, private, {"title": private_title})
    no_private = duplicate_query(a, "spot", city_id, private_title)
    check("duplicate hints never expose private contribution drafts", all(x["title"] != private_title for x in no_private["items"]))

    same_title = make_contribution(a, "spot")
    duplicate_payload = contribution_spot(exact_title, city_id)
    same_title = edit(a, same_title, duplicate_payload); same_title = submit(a, same_title)
    same_title_done = review(admin, same_title, "APPROVED")
    canonical.append(("spots", int(same_title_done["published_id"])))
    check("duplicate warning does not block submission or approval", same_title_done["status"] == "APPROVED")

    # Verify quota/image cleanup helpers have left all workflow tables structurally sound.
    check("new lifecycle APIs preserve SQLite integrity and foreign keys",
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
            if table == "guides": db.execute("DELETE FROM guide_spots WHERE guide_id=?", (ident,))
            db.execute(f"DELETE FROM {table} WHERE id=?", (ident,))
        if users:
            db.execute(f"DELETE FROM contribution_images WHERE owner_id IN ({marks})", users)
            db.execute(f"DELETE FROM users WHERE id IN ({marks})", users)
        for city_id in cities: db.execute("DELETE FROM cities WHERE id=?", (city_id,))
        db.commit()
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    for key in images: (DATA / "contribution-uploads" / key).unlink(missing_ok=True)
    for name in public_images: (DATA / "uploads" / name).unlink(missing_ok=True)

if checks:
    target = ROOT / "target"; target.mkdir(exist_ok=True)
    (target / "contribution-lifecycle-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
if failure is not None:
    print("FAIL after", len(checks), "contribution lifecycle checks:", failure, file=sys.stderr)
    raise failure
print("PASS", len(checks), "isolated contribution lifecycle checks")
