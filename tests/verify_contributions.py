"""隔离 QA 数据库上的投稿/审核 HTTP 与 SQLite 集成检查。

运行前必须由调用者显式设置 ROAM_BASE、ROAM_QA_DB、ROAM_DATA_DIR：
ROAM_BASE 只能是本机非 8818 端口，数据库和媒体目录必须位于项目 work/ 下，
且 ROAM_QA_DB 必须是 ROAM_DATA_DIR/guide.db。脚本不启动服务器。
"""
from __future__ import annotations

import base64
import binascii
import json
import os
import struct
import sqlite3
import sys
import time
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlencode

from http_client import requests

ROOT = Path(__file__).resolve().parents[1]


def require_isolated_environment() -> tuple[str, Path, Path]:
    base = os.environ.get("ROAM_BASE", "").rstrip("/")
    db_value = os.environ.get("ROAM_QA_DB", "")
    data_value = os.environ.get("ROAM_DATA_DIR", "")
    if not base or not db_value or not data_value:
        raise SystemExit("拒绝运行：必须显式设置 ROAM_BASE、ROAM_QA_DB、ROAM_DATA_DIR 到隔离 QA 环境。")
    from urllib.parse import urlsplit
    parsed = urlsplit(base)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} or not parsed.port or parsed.port == 8818:
        raise SystemExit("拒绝运行：ROAM_BASE 必须是本机 HTTP 地址且端口不能为正式默认端口 8818。")
    db_path = Path(db_value).resolve()
    data_dir = Path(data_value).resolve()
    work_root = (ROOT / "work").resolve()
    if db_path == (ROOT / "data" / "guide.db").resolve() or data_dir == (ROOT / "data").resolve():
        raise SystemExit("拒绝运行：不允许访问项目正式 data/guide.db 或 data 目录。")
    try:
        db_path.relative_to(work_root)
        data_dir.relative_to(work_root)
    except ValueError:
        raise SystemExit("拒绝运行：QA 数据库和媒体目录必须位于项目 work/ 隔离目录内。")
    if db_path != (data_dir / "guide.db").resolve():
        raise SystemExit("拒绝运行：ROAM_QA_DB 必须等于 ROAM_DATA_DIR/guide.db。")
    if not db_path.is_file():
        raise SystemExit("拒绝运行：隔离 QA 数据库不存在；请先由主代理准备并启动隔离服务。")
    return base, db_path, data_dir


BASE, DB, DATA = require_isolated_environment()
checks: list[str] = []
users: list[int] = []
contributions: list[int] = []
canonical: list[tuple[str, int]] = []
image_keys: list[str] = []
published_images: list[str] = []
fault_triggers: list[str] = []
qa_cities: list[int] = []
stamp = str(time.time_ns())[-9:]


def check(name: str, ok: bool = True) -> None:
    if not ok:
        raise AssertionError(name)
    checks.append(name)


def sql_one(query: str, *args):
    with sqlite3.connect(DB, timeout=10) as db:
        return db.execute(query, args).fetchone()


def sql_all(query: str, *args):
    with sqlite3.connect(DB, timeout=10) as db:
        return db.execute(query, args).fetchall()


class Client:
    def __init__(self):
        self.s = requests.Session()
        self.csrf = self.s.get(BASE + "/api/session").json()["csrf"]
        self.last_headers = {}

    def raw(self, method: str, path: str, body=None, *, files=None, csrf=True):
        headers = {"X-CSRF-Token": self.csrf} if csrf else {}
        response = self.s.request(method, BASE + path, json=body, files=files, headers=headers)
        self.last_headers = response.headers
        return response

    def call(self, method: str, path: str, body=None, status=200, *, files=None, csrf=True):
        response = self.raw(method, path, body, files=files, csrf=csrf)
        if response.status_code != status:
            raise AssertionError(f"{method} {path}: expected {status}, got {response.status_code}: {response.text[:300]}")
        if not response.content:
            return None
        try:
            data = response.json()
        except Exception:
            return response.content
        if isinstance(data, dict) and data.get("csrf"):
            self.csrf = data["csrf"]
        return data

    def login(self, name: str, password: str):
        return self.call("POST", "/api/auth/login", {"username": name, "password": password})


def reg(c: Client, suffix: str) -> tuple[int, str, str]:
    name = f"qa_contrib_{stamp}_{suffix}"
    password = "QaContrib!2026"
    user = c.call("POST", "/api/auth/register", {"username": name, "password": password})["user"]
    users.append(int(user["id"]))
    return int(user["id"]), name, password


def contribution(c: Client, kind: str, payload: dict | None = None, operation="CREATE", target_id=None):
    body = {"kind": kind, "operation": operation}
    if target_id is not None:
        body["target_id"] = target_id
    if payload is not None:
        body["payload"] = payload
    item = c.call("POST", "/api/me/contributions", body)
    contributions.append(int(item["id"]))
    return item


def edit(c: Client, item: dict, payload: dict) -> dict:
    return c.call("PUT", f"/api/me/contributions/{item['id']}", {"revision": item["revision"], "payload": payload})


def submit(c: Client, item: dict) -> dict:
    return c.call("POST", f"/api/me/contributions/{item['id']}/submit", {"revision": item["revision"]})


def decision(admin: Client, item: dict, value: str, note="", expected=200):
    return admin.call("POST", f"/api/admin/contributions/{item['id']}/review",
                      {"revision": item["revision"], "decision": value, "note": note}, status=expected)


def spot_payload(title: str, city_id: int, *, image="/assets/nanyang-hero.jpg", body="投稿专项目测正文" ):
    return {"city_id": city_id, "title": title, "intro": "QA 投稿简介", "description": body,
            "highlights": "QA 看点", "transportation": "QA 交通", "tips": "QA 提示", "image": image,
            "source_url": "", "theme": "人文历史", "duration_minutes": 120,
            "map_query": title, "gallery": []}


def guide_payload(title: str, city_id: int, spot_id: int):
    return {"city_id": city_id, "title": title, "subtitle": "QA 副标题", "intro": "QA 攻略简介",
            "image": "/assets/nanyang-hero.jpg", "source_url": "", "theme": "人文历史",
            "duration_minutes": 180, "sections": [{"title": "QA 小节", "body": "投稿全文搜索唯一短语" + stamp}],
            "spot_ids": [spot_id]}


def food_payload(title: str, city_id: int):
    return {"city_id": city_id, "title": title, "category": "小吃", "intro": "QA 美食简介",
            "description": "QA 美食正文", "taste": "清香", "tips": "QA 用餐提示", "image": "", "source_url": ""}


def search(c: Client, q: str, kind="spot"):
    return c.call("GET", "/api/search?" + urlencode({"q": q, "type": kind, "size": 24}))


def public_foods(c: Client, title: str):
    return c.call("GET", "/api/foods?" + urlencode({"q": title, "size": 24}))


def png_bytes(width: int, height: int) -> bytes:
    def chunk(tag: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", binascii.crc32(tag + payload) & 0xffffffff)
    compressor = zlib.compressobj(level=9)
    row = b"\x00" + b"\x00\x00\x00" * width
    packed = bytearray()
    for _ in range(height):
        packed.extend(compressor.compress(row))
    packed.extend(compressor.flush())
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", bytes(packed)) + chunk(b"IEND", b"")


def jpeg_dimensions(data: bytes) -> tuple[int, int]:
    if not data.startswith(b"\xff\xd8"):
        raise AssertionError("uploaded result is not JPEG")
    i = 2
    sof = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        while i < len(data) and data[i] == 0xFF:
            i += 1
        marker = data[i]
        i += 1
        if marker in {0xD8, 0xD9, 0x01} or 0xD0 <= marker <= 0xD7:
            continue
        length = int.from_bytes(data[i:i + 2], "big")
        if marker in sof:
            height = int.from_bytes(data[i + 3:i + 5], "big")
            width = int.from_bytes(data[i + 5:i + 7], "big")
            return width, height
        if length < 2:
            break
        i += length
    raise AssertionError("JPEG dimensions not found")


def main():
    admin = Client()
    admin.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    owner, owner_name, owner_password = reg(Client(), "a")
    other, other_name, other_password = reg(Client(), "b")
    alice = Client(); alice.login(owner_name, owner_password)
    bob = Client(); bob.login(other_name, other_password)
    guest = Client()

    # Choose an already-public city and same-city public spot from this QA database.
    cities = guest.call("GET", "/api/cities")
    check("isolated DB contains a public city", bool(cities))
    city = next((x for x in cities if int(x.get("id", 0)) == 3), cities[0])
    city_id = int(city["id"])
    result = guest.call("GET", "/api/search?" + urlencode({"q": "", "type": "spot", "city": city_id, "size": 24}))
    # Search DTOs intentionally omit city_id, so request the authoritative city filter.
    same_city_spots = result.get("items", [])
    check("public same-city spot available for guide association", bool(same_city_spots))
    guide_spot_id = int(same_city_spots[0]["id"])

    # Authentication, role checks, CSRF, and account ownership.
    guest.call("GET", "/api/me/contributions", status=401)
    guest.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"}, status=401)
    guest.call("GET", f"/api/contributions/source/spot/{guide_spot_id}", status=401)
    alice.call("GET", "/api/admin/contributions", status=403)
    alice.call("GET", "/api/admin/contributions/1", status=403)
    alice.call("POST", "/api/admin/contributions/1/review", {"revision": 1, "decision": "APPROVED"}, status=403)
    no_csrf = alice.raw("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"}, csrf=False)
    check("authenticated mutation enforces CSRF", no_csrf.status_code == 403)

    empty_list = alice.call("GET", "/api/me/contributions?status=DRAFT&kind=spot")
    check("empty personal filter returns stable empty pagination", empty_list["items"] == [] and empty_list["total"] == 0 and empty_list["page"] == 1)
    blank = contribution(alice, "spot")
    check("new contribution is empty DRAFT with server-owned fields", blank["status"] == "DRAFT" and blank["revision"] == 1 and blank["payload"] == {})
    alice.call("GET", f"/api/me/contributions/{blank['id']}")
    bob.call("GET", f"/api/me/contributions/{blank['id']}", status=404)
    bob.call("PUT", f"/api/me/contributions/{blank['id']}", {"revision": 1, "payload": spot_payload("越权", city_id)}, status=404)
    alice.call("PUT", f"/api/me/contributions/{blank['id']}",
               {"revision": 1, "payload": {"published": True, "status": "APPROVED", "published_id": 9}}, status=400)
    partial = alice.call("PUT", f"/api/me/contributions/{blank['id']}", {"revision": 1, "payload": {"title": "QA仅修改标题" + stamp}})
    check("partial draft payload with only a title is accepted", partial["payload"] == {"title": "QA仅修改标题" + stamp})
    alice.call("POST", f"/api/me/contributions/{blank['id']}/submit", {"revision": partial["revision"]}, status=400)
    alice.call("PUT", f"/api/me/contributions/{blank['id']}", {"revision": partial["revision"], "payload": {"city_id": city_id + 0.5}}, status=400)
    alice.call("PUT", f"/api/me/contributions/{blank['id']}", {"revision": partial["revision"], "payload": {"duration_minutes": 120.5}}, status=400)
    alice.call("POST", f"/api/me/contributions/{blank['id']}/submit", {"revision": partial["revision"] + 0.5}, status=400)
    numeric_guide = contribution(alice, "guide")
    alice.call("PUT", f"/api/me/contributions/{numeric_guide['id']}",
               {"revision": numeric_guide["revision"], "payload": {"spot_ids": [guide_spot_id + 0.5]}}, status=400)
    check("city, duration, spot ids and revision require integers")
    check("other owner cannot read or mutate and reserved publish fields are rejected")

    # Image ownership and upload validation. Keep the private upload for one approved test spot.
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=")
    uploaded = alice.call("POST", "/api/me/contribution-images", files={"file": ("qa.png", __import__("io").BytesIO(png), "image/png")})
    private_url = uploaded["url"]
    key = private_url.rsplit("/", 1)[-1]
    image_keys.append(key)
    vertical_upload = alice.call("POST", "/api/me/contribution-images",
                                 files={"file": ("vertical.png", __import__("io").BytesIO(png_bytes(160, 1800)), "image/png")})
    vertical_key = vertical_upload["url"].rsplit("/", 1)[-1]
    image_keys.append(vertical_key)
    resized = jpeg_dimensions(alice.call("GET", vertical_upload["url"]))
    check(f"portrait upload is proportionally resized with longest side at most 1600 (actual {resized})", max(resized) == 1600 and min(resized) < 1600)
    guest.call("GET", "/api/contribution-images/" + key, status=401)
    bob.call("GET", "/api/contribution-images/" + key, status=404)
    alice.call("GET", "/api/contribution-images/" + key)
    admin.call("GET", "/api/contribution-images/" + key)
    alice.call("POST", "/api/me/contribution-images", files={"file": ("bad.gif", __import__("io").BytesIO(b"GIF89a"), "image/gif")}, status=400)
    oversized_pixels = alice.raw("POST", "/api/me/contribution-images",
                                 files={"file": ("over-30mp.png", __import__("io").BytesIO(png_bytes(6000, 5001)), "image/png")})
    check("image above 30 MP is rejected", oversized_pixels.status_code == 400)
    too_large = alice.raw("POST", "/api/me/contribution-images",
                          files={"file": ("large.png", __import__("io").BytesIO(b"x" * (5 * 1024 * 1024 + 1)), "image/png")})
    check("oversized image upload is rejected at the multipart or controller limit", too_large.status_code in {400, 413})
    check("private image access is owner/admin only and invalid upload types or size are rejected")

    # Pending drafts stay private, cannot be edited, and cannot be submitted twice.
    spot_title = "QA投稿景点" + stamp
    spot = contribution(alice, "spot")
    p = spot_payload(spot_title, city_id, image=private_url, body="QA景点正文" + stamp)
    spot = edit(alice, spot, p)
    alice.call("PUT", f"/api/me/contributions/{spot['id']}", {"revision": 1, "payload": p}, status=409)
    # A foreign private-image URL must fail even though the URL is syntactically valid.
    draft_b = contribution(bob, "spot")
    bob.call("PUT", f"/api/me/contributions/{draft_b['id']}",
             {"revision": draft_b["revision"], "payload": spot_payload("QA他人图片", city_id, image=private_url)}, status=400)
    alice.call("PUT", f"/api/me/contributions/{blank['id']}",
               {"revision": partial["revision"], "payload": {"image": "/api/contribution-images/00000000-0000-0000-0000-000000000000.jpg"}}, status=400)
    bad_image_revision = spot["revision"]
    p["gallery"] = ["/api/contribution-images/00000000-0000-0000-0000-000000000000.jpg"]
    alice.call("PUT", f"/api/me/contributions/{spot['id']}", {"revision": spot["revision"], "payload": p}, status=400)
    spot = alice.call("GET", f"/api/me/contributions/{spot['id']}")
    check("invalid private image reference is rejected without changing the draft", spot["payload"]["gallery"] == [] and spot["revision"] == bad_image_revision)
    p["gallery"] = []
    spot = edit(alice, spot, p)
    spot = submit(alice, spot)
    check("valid spot submission transitions to PENDING", spot["status"] == "PENDING")
    alice.call("PUT", f"/api/me/contributions/{spot['id']}", {"revision": spot["revision"], "payload": p}, status=409)
    alice.call("POST", f"/api/me/contributions/{spot['id']}/submit", {"revision": spot["revision"]}, status=409)
    alice.call("POST", f"/api/me/contributions/{spot['id']}/withdraw", {"revision": spot["revision"]}, status=200)
    withdrawn = alice.call("GET", f"/api/me/contributions/{spot['id']}")
    check("withdraw returns pending proposal to DRAFT without losing content", withdrawn["status"] == "DRAFT" and withdrawn["payload"]["title"] == spot_title)
    spot = submit(alice, withdrawn)
    check("withdrawn proposal can be resubmitted", spot["status"] == "PENDING")
    check("draft/pending items never leak into public search", search(guest, spot_title)["total"] == 0)
    mine = alice.call("GET", "/api/me/contributions?status=PENDING&kind=spot&page=1&size=1")
    check("personal list filters and pagination describe own submissions", mine["total"] == 1 and mine["items"][0]["id"] == spot["id"] and mine["size"] == 1)
    admin.call("GET", "/api/admin/contributions?status=PENDING&kind=spot&page=1&size=1")
    admin.call("GET", f"/api/admin/contributions/{spot['id']}")
    filtered = admin.call("GET", "/api/admin/contributions?" + urlencode({"status": "PENDING", "kind": "spot", "q": spot_title, "page": 1, "size": 5}))
    no_match = admin.call("GET", "/api/admin/contributions?" + urlencode({"status": "PENDING", "kind": "spot", "q": "QA-no-such-contribution-" + stamp}))
    metachar = admin.call("GET", "/api/admin/contributions?" + urlencode({"status": "PENDING", "kind": "spot", "q": "%_\\"}))
    check("admin q filtering and SQLite ESCAPE handle exact text and metacharacters", filtered["total"] == 1 and filtered["items"][0]["id"] == spot["id"] and no_match["total"] == 0 and isinstance(metachar["total"], int))

    # Required return reason, review history, resubmission, and canonical spot publication.
    decision(admin, spot, "RETURNED", "", expected=400)
    returned = decision(admin, spot, "RETURNED", "请补充可核实的现场信息。" + stamp)
    check("return decision persists note and review history", returned["status"] == "RETURNED" and len(returned["reviews"]) == 1 and returned["reviews"][0]["decision"] == "RETURNED")
    returned = alice.call("GET", f"/api/me/contributions/{spot['id']}")
    p["description"] += "补充说明"
    returned = edit(alice, returned, p)
    spot = submit(alice, returned)
    approved = decision(admin, spot, "APPROVED")
    canonical.append(("spots", int(approved["published_id"])))
    check("approved CREATE spot is published and retains review history", approved["status"] == "APPROVED" and len(approved["reviews"]) == 2)
    approval_audits = sql_one("SELECT count(*) FROM admin_audit_logs WHERE resource='contributions' AND target_id=? AND action='APPROVE_CONTRIBUTION'", spot["id"])[0]
    check(f"successful approval is represented in admin audit log (rows={approval_audits})", approval_audits == 1)
    response_request_id = admin.last_headers.get("X-Request-ID")
    audit_request_id = sql_one("SELECT request_id FROM admin_audit_logs WHERE resource='contributions' AND target_id=? AND action='APPROVE_CONTRIBUTION' ORDER BY id DESC LIMIT 1", spot["id"])[0]
    check("approval X-Request-ID matches this QA contribution's audit request_id",
          bool(response_request_id) and response_request_id == audit_request_id)
    pub_spot = guest.call("GET", f"/api/spots/{approved['published_id']}")
    check("approved spot appears in public detail and search", pub_spot["id"] == approved["published_id"] and search(guest, spot_title)["total"] == 1)
    check("approved private upload is materialized as public upload", pub_spot["image"].startswith("/uploads/") and guest.call("GET", pub_spot["image"]) is not None)
    published_images.append(pub_spot["image"].rsplit("/", 1)[-1])
    alice.call("GET", "/api/contribution-images/" + key)
    bob.call("GET", "/api/contribution-images/" + key, status=404)
    source = alice.call("GET", f"/api/contributions/source/spot/{approved['published_id']}")
    check("authenticated source endpoint returns current public canonical source", source["target_id"] == approved["published_id"] and source["payload"]["title"] == spot_title)
    decision(admin, spot, "APPROVED", expected=409)
    check("approval replay cannot create a second canonical record", sql_one("SELECT count(*) FROM spots WHERE title=?", spot_title)[0] == 1)

    # Food CREATE approval (optional cover) and searchable public list.
    food_title = "QA投稿美食" + stamp
    food = contribution(alice, "food")
    food = edit(alice, food, food_payload(food_title, city_id))
    food = submit(alice, food)
    food_done = decision(admin, food, "APPROVED")
    canonical.append(("foods", int(food_done["published_id"])))
    check("approved CREATE food is in canonical public food list", public_foods(guest, food_title)["total"] == 1 and guest.call("GET", f"/api/foods/{food_done['published_id']}")["id"] == food_done["published_id"])

    # Guide approval requires an existing same-city published spot; fulltext indexes sections.
    guide_title = "QA投稿攻略" + stamp
    guide = contribution(alice, "guide")
    guide = edit(alice, guide, guide_payload(guide_title, city_id, guide_spot_id))
    guide = submit(alice, guide)
    guide_done = decision(admin, guide, "APPROVED")
    canonical.append(("guides", int(guide_done["published_id"])))
    detail = guest.call("GET", f"/api/guides/{guide_done['published_id']}")
    check("approved CREATE guide preserves same-city spot relation", detail["id"] == guide_done["published_id"] and any(int(x["id"]) == guide_spot_id for x in detail["spots"]))
    check("approved guide body enters fulltext search", search(guest, "投稿全文搜索唯一短语" + stamp, "guide")["total"] == 1)

    returned_filter = contribution(alice, "food")
    returned_filter = edit(alice, returned_filter, food_payload("QA筛选退回" + stamp, city_id))
    returned_filter = submit(alice, returned_filter)
    returned_filter = decision(admin, returned_filter, "RETURNED", "QA筛选退回样本")
    default_admin_page = admin.call("GET", "/api/admin/contributions?size=50")
    empty_status_page = admin.call("GET", "/api/admin/contributions?" + urlencode({"status": "", "size": 50}))
    default_rows = {int(x["id"]): x for x in default_admin_page["items"]}
    all_rows = {int(x["id"]): x for x in empty_status_page["items"]}
    check("admin status omission defaults to PENDING only", all(x["status"] == "PENDING" for x in default_rows.values()))
    check("explicit empty admin status includes QA DRAFT, RETURNED and APPROVED rows",
          blank["id"] in all_rows and returned_filter["id"] in all_rows and food_done["id"] in all_rows
          and {all_rows[blank["id"]]["status"], all_rows[returned_filter["id"]]["status"], all_rows[food_done["id"]]["status"]} == {"DRAFT", "RETURNED", "APPROVED"})

    # A hidden QA-only city cannot be selected to publish content.
    with sqlite3.connect(DB) as db:
        hidden_city = db.execute("INSERT INTO cities(region_code,province_code,published,slug,name,province,tagline,description,image) VALUES(NULL,'QA',0,?,?,?,?,?,?)",
                                 ("qa-contribution-hidden-" + stamp, "QA未公开城市" + stamp, "QA", "QA暂不公开", "QA不作为公开内容归属", "/assets/nanyang-hero.jpg")).lastrowid
        db.commit()
    qa_cities.append(int(hidden_city))
    hidden = contribution(alice, "food")
    hidden = edit(alice, hidden, food_payload("QA隐藏城市投稿" + stamp, int(hidden_city)))
    alice.call("POST", f"/api/me/contributions/{hidden['id']}/submit", {"revision": hidden["revision"]}, status=404)
    check("submission rejects content assigned to unpublished city", sql_one("SELECT published FROM cities WHERE id=?", hidden_city)[0] == 0)

    # Optimistic concurrency against an UPDATE target; proposal survives rebase.
    update_target_id = int(approved["published_id"])
    original = guest.call("GET", f"/api/spots/{update_target_id}")
    update = contribution(alice, "spot", operation="UPDATE", target_id=update_target_id)
    check("UPDATE draft captures canonical baseline and revision", update["target_revision"] == original["revision"] and update["payload"].get("title") == original["title"])
    proposal = dict(update["payload"])
    proposal["title"] = "QA投稿修改提议" + stamp
    proposal["description"] += "保留提议内容"
    update = edit(alice, update, proposal)
    update = submit(alice, update)
    # Update target only within this run's QA content, through the existing admin API.
    admin_body = {key: original[key] for key in ("city_id", "title", "intro", "description", "highlights", "transportation", "tips", "image", "source_url", "theme", "duration_minutes", "map_query", "gallery") if key in original}
    admin_body.update({"published": True, "revision": original["revision"], "description": original["description"] + "管理员并发修订"})
    admin.call("PUT", f"/api/admin/spots/{update_target_id}", admin_body)
    stale = decision(admin, update, "APPROVED", expected=409)
    check("stale target revision blocks overwrite", sql_one("SELECT description FROM spots WHERE id=?", update_target_id)[0] == admin_body["description"])
    stale_return = decision(admin, update, "RETURNED", "原资料刚有更新，请基于新版本复核。" + stamp)
    check("stale proposal can be returned for rebase", stale_return["status"] == "RETURNED")
    returned = alice.call("GET", f"/api/me/contributions/{update['id']}")
    rebased = alice.call("POST", f"/api/me/contributions/{update['id']}/rebase", {"revision": returned["revision"]})
    check("rebase updates base snapshot but preserves proposal", rebased["target_revision"] > update["target_revision"] and rebased["payload"]["title"] == proposal["title"] and rebased["base_snapshot"]["description"] == admin_body["description"])
    update = submit(alice, rebased)
    update_done = decision(admin, update, "APPROVED")
    check("rebased UPDATE is approved onto same canonical id", update_done["published_id"] == update_target_id and guest.call("GET", f"/api/spots/{update_target_id}")["title"] == proposal["title"])

    # A proposal submitted against our own QA target cannot republish it after an admin unpublishes it.
    latest = guest.call("GET", f"/api/spots/{update_target_id}")
    down_proposal = contribution(alice, "spot", operation="UPDATE", target_id=update_target_id)
    down_proposal = edit(alice, down_proposal, dict(down_proposal["payload"], title="QA下架后不得复活" + stamp))
    down_proposal = submit(alice, down_proposal)
    unpublish_body = {key: latest[key] for key in ("city_id", "title", "intro", "description", "highlights", "transportation", "tips", "image", "source_url", "theme", "duration_minutes", "map_query", "gallery") if key in latest}
    unpublish_body.update({"published": False, "revision": latest["revision"]})
    admin.call("PUT", f"/api/admin/spots/{update_target_id}", unpublish_body)
    decision(admin, down_proposal, "APPROVED", expected=409)
    check("approval cannot revive an unpublished update target", sql_one("SELECT published FROM spots WHERE id=?", update_target_id)[0] == 0)

    # A two-sided race must have one winner (withdraw OR review), with no duplicate publication.
    race_title = "QA投稿并发" + stamp
    race = contribution(alice, "food")
    race = edit(alice, race, food_payload(race_title, city_id))
    race = submit(alice, race)
    withdraw_client = Client(); withdraw_client.login(owner_name, owner_password)
    admin_race = Client(); admin_race.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    from threading import Barrier
    barrier = Barrier(2)
    def do_withdraw():
        barrier.wait()
        return withdraw_client.raw("POST", f"/api/me/contributions/{race['id']}/withdraw", {"revision": race["revision"]})
    def do_review():
        barrier.wait()
        return admin_race.raw("POST", f"/api/admin/contributions/{race['id']}/review", {"revision": race["revision"], "decision": "APPROVED", "note": ""})
    with ThreadPoolExecutor(max_workers=2) as pool:
        wr, rr = list(pool.map(lambda fn: fn(), [do_withdraw, do_review]))
    check("withdraw/review race has exactly one committed transition", sorted([wr.status_code, rr.status_code]) == [200, 409])
    race_state = alice.call("GET", f"/api/me/contributions/{race['id']}")
    if race_state["status"] == "APPROVED":
        race_id = int(race_state["published_id"]); canonical.append(("foods", race_id))
        check("review race produced exactly one canonical food", sql_one("SELECT count(*) FROM foods WHERE title=?", race_title)[0] == 1)
    else:
        check("withdraw winner returns item to DRAFT without publication", race_state["status"] == "DRAFT" and sql_one("SELECT count(*) FROM foods WHERE title=?", race_title)[0] == 0)

    # Two reviewers racing the same revision must create exactly one canonical row and one audit pair.
    double_title = "QA投稿双审" + stamp
    double = contribution(alice, "food")
    double = edit(alice, double, food_payload(double_title, city_id))
    double = submit(alice, double)
    admin_one = Client(); admin_one.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    admin_two = Client(); admin_two.login("admin", os.environ.get("ROAM_QA_ADMIN_PASSWORD", "TravelGuide!2026"))
    double_barrier = Barrier(2)
    def approve_with(client):
        double_barrier.wait()
        return client.raw("POST", f"/api/admin/contributions/{double['id']}/review", {"revision": double["revision"], "decision": "APPROVED", "note": ""})
    with ThreadPoolExecutor(max_workers=2) as pool:
        d1, d2 = list(pool.map(approve_with, [admin_one, admin_two]))
    check("concurrent duplicate approvals yield one success and one conflict", sorted([d1.status_code, d2.status_code]) == [200, 409])
    double_final = alice.call("GET", f"/api/me/contributions/{double['id']}")
    canonical.append(("foods", int(double_final["published_id"])))
    check("duplicate approval writes exactly one canonical row/review/audit event", sql_one("SELECT count(*) FROM foods WHERE title=?", double_title)[0] == 1 and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", double["id"])[0] == 1 and sql_one("SELECT count(*) FROM admin_audit_logs WHERE resource='contributions' AND target_id=?", double["id"])[0] == 1)

    # Banned accounts lose access immediately, ordinary user cannot gain review role through payload.
    admin.call("POST", f"/api/admin/users/{other}/status", {"status": "BANNED"})
    bob.call("GET", "/api/me/contributions", status=401)
    admin.call("POST", f"/api/admin/users/{other}/status", {"status": "ACTIVE"})
    bob = Client(); bob.login(other_name, other_password)
    check("banned owner cannot use contribution APIs; admin can restore own QA account", bob.call("GET", "/api/me/contributions")["total"] >= 0)
    reset = admin.call("POST", f"/api/admin/users/{other}/reset-password")
    bob = Client(); bob.login(other_name, reset["temporary_password"])
    bob.call("GET", "/api/me/contributions")
    bob.call("POST", "/api/me/contributions", {"kind": "food", "operation": "CREATE"}, status=409)
    password_update = bob.call("POST", "/api/user/password", {"old_password": reset["temporary_password"], "new_password": "QaContrib!Changed2026"})
    check("temporary-password account can only mutate contributions after changing its password", password_update["user"]["must_change_password"] is False)
    forced_draft = contribution(bob, "food")
    check("password-updated QA owner can resume contribution creation", forced_draft["status"] == "DRAFT")
    admin_list = admin.call("GET", "/api/admin/contributions?status=APPROVED&kind=food&page=1&size=1")
    check("admin list supports status/kind filters and page size", admin_list["page"] == 1 and admin_list["size"] == 1 and len(admin_list["items"]) <= 1)

    # A targeted trigger fails the audit insert after materialization; canonical and review writes roll back.
    atomic_title = "QA投稿事务回滚" + stamp
    atomic = contribution(alice, "food")
    atomic = edit(alice, atomic, food_payload(atomic_title, city_id))
    atomic = submit(alice, atomic)
    trigger = "qa_contrib_fail_" + stamp
    fault_triggers.append(trigger)
    with sqlite3.connect(DB) as db:
        db.execute(f"CREATE TRIGGER {trigger} BEFORE INSERT ON contribution_reviews WHEN NEW.contribution_id={int(atomic['id'])} BEGIN SELECT RAISE(ABORT,'QA targeted failure'); END")
    failed = admin.raw("POST", f"/api/admin/contributions/{atomic['id']}/review", {"revision": atomic["revision"], "decision": "APPROVED", "note": ""})
    check("forced audit failure returns an error and leaves review pending", failed.status_code >= 400 and sql_one("SELECT status FROM contributions WHERE id=?", atomic["id"])[0] == "PENDING")
    check("forced audit failure rolls canonical write back", sql_one("SELECT count(*) FROM foods WHERE title=?", atomic_title)[0] == 0 and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", atomic["id"])[0] == 0)

    # Fail the actual admin audit log insert after the canonical row and review row were attempted.
    atomic_image_title = "QA投稿图片原子回滚" + stamp
    atomic_image = contribution(alice, "spot")
    atomic_image = edit(alice, atomic_image, spot_payload(atomic_image_title, city_id, image=private_url))
    atomic_image = submit(alice, atomic_image)
    audit_trigger = "qa_contrib_fail_audit_" + stamp
    fault_triggers.append(audit_trigger)
    with sqlite3.connect(DB) as db:
        db.execute(f"CREATE TRIGGER {audit_trigger} BEFORE INSERT ON admin_audit_logs WHEN NEW.resource='contributions' AND NEW.target_id='{int(atomic_image['id'])}' BEGIN SELECT RAISE(ABORT,'QA audit-log failure'); END")
        db.commit()
    before_uploads = {p.name for p in (DATA / "uploads").glob("*")}
    audit_failed = admin.raw("POST", f"/api/admin/contributions/{atomic_image['id']}/review", {"revision": atomic_image["revision"], "decision": "APPROVED", "note": ""})
    after_uploads = {p.name for p in (DATA / "uploads").glob("*")}
    atomic_image_status = sql_one("SELECT status FROM contributions WHERE id=?", atomic_image["id"])[0]
    check(f"forced admin_audit_logs failure is surfaced without approving (HTTP {audit_failed.status_code}, state {atomic_image_status}, body {audit_failed.text[:180]})",
          audit_failed.status_code >= 400 and atomic_image_status == "PENDING")
    check("admin audit failure rolls back canonical and review rows", sql_one("SELECT count(*) FROM spots WHERE title=?", atomic_image_title)[0] == 0 and sql_one("SELECT count(*) FROM contribution_reviews WHERE contribution_id=?", atomic_image["id"])[0] == 0 and sql_one("SELECT count(*) FROM admin_audit_logs WHERE resource='contributions' AND target_id=?", atomic_image["id"])[0] == 0)
    check("failed approval removes copied public image file", after_uploads == before_uploads)

    # Database migration, integrity, and foreign key sanity.
    tables = {row[0] for row in sql_all("SELECT name FROM sqlite_master WHERE type='table'")}
    check("contribution migration tables and indexes exist", {"contributions", "contribution_reviews", "contribution_images"}.issubset(tables))
    check("SQLite migration leaves foreign keys and integrity valid", sql_one("PRAGMA integrity_check")[0] == "ok" and not sql_all("PRAGMA foreign_key_check"))

    duplicate_guide = contribution(alice, "guide")
    duplicate_payload = guide_payload("QA重复景点关联" + stamp, city_id, guide_spot_id)
    duplicate_payload["spot_ids"] = [guide_spot_id, guide_spot_id]
    duplicate_response = alice.call("PUT", f"/api/me/contributions/{duplicate_guide['id']}",
                                    {"revision": duplicate_guide["revision"], "payload": duplicate_payload}, status=400)
    check("duplicate guide spot links are rejected while saving the draft", duplicate_response is not None)


failure = None
try:
    main()
except BaseException as exc:
    failure = exc
finally:
    # Remove only this run's trigger, test rows, and generated media from the isolated QA DB.
    with sqlite3.connect(DB, timeout=10) as db:
        db.execute("PRAGMA foreign_keys=ON")
        for name in fault_triggers:
            db.execute(f"DROP TRIGGER IF EXISTS {name}")
        for cid in contributions:
            db.execute("DELETE FROM contributions WHERE id=?", (cid,))
            db.execute("DELETE FROM admin_audit_logs WHERE resource='contributions' AND target_id=?", (cid,))
        for table, ident in reversed(canonical):
            if table == "guides":
                db.execute("DELETE FROM guide_spots WHERE guide_id=?", (ident,))
            db.execute(f"DELETE FROM {table} WHERE id=?", (ident,))
        if users:
            marks = ",".join("?" for _ in users)
            db.execute(f"DELETE FROM notifications WHERE user_id IN ({marks})", users)
            db.execute(f"DELETE FROM contribution_images WHERE owner_id IN ({marks})", users)
            db.execute(f"DELETE FROM users WHERE id IN ({marks})", users)
        for city_id in qa_cities:
            db.execute("DELETE FROM cities WHERE id=?", (city_id,))
        db.commit()
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    for name in fault_triggers:
        with sqlite3.connect(DB) as db:
            assert db.execute("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name=?", (name,)).fetchone()[0] == 0
    for key in image_keys:
        (DATA / "contribution-uploads" / key).unlink(missing_ok=True)
    for filename in published_images:
        (DATA / "uploads" / filename).unlink(missing_ok=True)

if checks:
    target = ROOT / "target"
    target.mkdir(exist_ok=True)
    (target / "contributions-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
if failure is not None:
    print("FAIL after", len(checks), "isolated contribution HTTP/SQLite checks:", failure, file=sys.stderr)
    raise failure
print("PASS", len(checks), "isolated contribution HTTP/SQLite checks")
