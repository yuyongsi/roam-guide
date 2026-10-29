CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 username TEXT NOT NULL UNIQUE COLLATE NOCASE,
 password_hash TEXT NOT NULL,
 role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN')),
 status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','BANNED')),
 auth_version INTEGER NOT NULL DEFAULT 1,
 must_change_password INTEGER NOT NULL DEFAULT 0 CHECK(must_change_password IN (0,1)),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS cities (
 id INTEGER PRIMARY KEY,
 region_code TEXT, province_code TEXT NOT NULL DEFAULT '', published INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1,
 slug TEXT NOT NULL UNIQUE, name TEXT NOT NULL, province TEXT NOT NULL,
 tagline TEXT NOT NULL, description TEXT NOT NULL, image TEXT NOT NULL,
 sort_order INTEGER NOT NULL DEFAULT 0,
 food TEXT NOT NULL DEFAULT '', travel_advice TEXT NOT NULL DEFAULT '',
 visit_advice TEXT NOT NULL DEFAULT '', food_source TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS spots (
 id INTEGER PRIMARY KEY AUTOINCREMENT, revision INTEGER NOT NULL DEFAULT 1, city_id INTEGER NOT NULL REFERENCES cities(id),
 map_query TEXT NOT NULL DEFAULT '',
 title TEXT NOT NULL, intro TEXT NOT NULL, description TEXT NOT NULL,
 highlights TEXT NOT NULL DEFAULT '', transportation TEXT NOT NULL DEFAULT '',
 tips TEXT NOT NULL DEFAULT '', image TEXT NOT NULL, source_url TEXT NOT NULL DEFAULT '',
 published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
 theme TEXT NOT NULL DEFAULT '人文历史', duration_minutes INTEGER NOT NULL DEFAULT 120,
 gallery TEXT NOT NULL DEFAULT '[]',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS guides (
 id INTEGER PRIMARY KEY AUTOINCREMENT, revision INTEGER NOT NULL DEFAULT 1, city_id INTEGER NOT NULL REFERENCES cities(id),
 title TEXT NOT NULL, subtitle TEXT NOT NULL DEFAULT '', intro TEXT NOT NULL,
 image TEXT NOT NULL, sections TEXT NOT NULL DEFAULT '[]',
 source_url TEXT NOT NULL DEFAULT '', published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
 theme TEXT NOT NULL DEFAULT '人文历史', duration_minutes INTEGER NOT NULL DEFAULT 120,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS guide_spots (
 guide_id INTEGER NOT NULL REFERENCES guides(id) ON DELETE CASCADE,
 spot_id INTEGER NOT NULL REFERENCES spots(id), sort_order INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(guide_id,spot_id)
);
CREATE TABLE IF NOT EXISTS spot_favorites (
 user_id INTEGER NOT NULL REFERENCES users(id), spot_id INTEGER NOT NULL REFERENCES spots(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(user_id,spot_id)
);
CREATE TABLE IF NOT EXISTS guide_favorites (
 user_id INTEGER NOT NULL REFERENCES users(id), guide_id INTEGER NOT NULL REFERENCES guides(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(user_id,guide_id)
);
CREATE TABLE IF NOT EXISTS comments (
 id INTEGER PRIMARY KEY AUTOINCREMENT, guide_id INTEGER NOT NULL REFERENCES guides(id),
 user_id INTEGER NOT NULL REFERENCES users(id), body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 1000),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_spots_city_published ON spots(city_id,published);
CREATE INDEX IF NOT EXISTS idx_guides_city_published ON guides(city_id,published);
CREATE INDEX IF NOT EXISTS idx_comments_guide ON comments(guide_id,created_at);
CREATE INDEX IF NOT EXISTS idx_comments_user ON comments(user_id,created_at);
CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS trips (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id),
 title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 60),
 days INTEGER NOT NULL DEFAULT 1 CHECK(days BETWEEN 1 AND 14),
 start_date TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
 revision INTEGER NOT NULL DEFAULT 1, checklist TEXT NOT NULL DEFAULT '[]',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS trip_items (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 spot_id INTEGER NOT NULL REFERENCES spots(id),
 day_number INTEGER NOT NULL CHECK(day_number BETWEEN 1 AND 14),
 sort_order INTEGER NOT NULL DEFAULT 0,
 note TEXT NOT NULL DEFAULT '',
 UNIQUE(trip_id,spot_id)
);
CREATE INDEX IF NOT EXISTS idx_trips_user ON trips(user_id,updated_at);
CREATE INDEX IF NOT EXISTS idx_trip_items_order ON trip_items(trip_id,day_number,sort_order);
CREATE TABLE IF NOT EXISTS browse_history (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 spot_id INTEGER REFERENCES spots(id), guide_id INTEGER REFERENCES guides(id),
 visited_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 CHECK((spot_id IS NULL) != (guide_id IS NULL)),
 UNIQUE(user_id,spot_id), UNIQUE(user_id,guide_id)
);
CREATE INDEX IF NOT EXISTS idx_browse_history_user ON browse_history(user_id,visited_at);

CREATE TABLE IF NOT EXISTS comment_likes (
 comment_id INTEGER NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(comment_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_comment_likes_user ON comment_likes(user_id);

CREATE TABLE IF NOT EXISTS feedback (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id),
 category TEXT NOT NULL CHECK(category IN ('内容纠错','功能建议','使用问题')),
 body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 1000),
 page_path TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RESOLVED')),
 admin_reply TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback(user_id,created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_status ON feedback(status,created_at);

CREATE TABLE IF NOT EXISTS social_posts (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id),
 city_id INTEGER REFERENCES cities(id),
 category TEXT NOT NULL CHECK(category IN ('游记','提问','经验')),
 title TEXT NOT NULL CHECK(length(title) BETWEEN 3 AND 80),
 body TEXT NOT NULL CHECK(length(body) BETWEEN 10 AND 12000),
 image TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL DEFAULT 'PUBLISHED' CHECK(status IN ('PUBLISHED','HIDDEN','DELETED')),
 revision INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS social_comments (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 post_id INTEGER NOT NULL REFERENCES social_posts(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id),
 body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 1000),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS social_likes (
 post_id INTEGER NOT NULL REFERENCES social_posts(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 PRIMARY KEY(post_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_social_posts_public ON social_posts(status,city_id,category,id);
CREATE INDEX IF NOT EXISTS idx_social_posts_user ON social_posts(user_id,id);
CREATE INDEX IF NOT EXISTS idx_social_comments_post ON social_comments(post_id,id);

CREATE TABLE IF NOT EXISTS foods (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 city_id INTEGER NOT NULL REFERENCES cities(id),
 title TEXT NOT NULL,
 category TEXT NOT NULL CHECK(category IN ('小吃','主食','菜肴','甜点')),
 intro TEXT NOT NULL, description TEXT NOT NULL,
 taste TEXT NOT NULL DEFAULT '', tips TEXT NOT NULL DEFAULT '',
 image TEXT NOT NULL DEFAULT '', source_url TEXT NOT NULL DEFAULT '',
 published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
 revision INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS food_favorites (
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 food_id INTEGER NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(user_id,food_id)
);
CREATE INDEX IF NOT EXISTS idx_foods_city_published ON foods(city_id,published,category);

CREATE TABLE IF NOT EXISTS trip_budgets (
 trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
 people INTEGER NOT NULL DEFAULT 1 CHECK(people BETWEEN 1 AND 99),
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>=1),
 notes TEXT NOT NULL DEFAULT '',
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS trip_budget_items (
 day_number INTEGER NOT NULL DEFAULT 0,
 spot_id INTEGER NOT NULL DEFAULT 0,
 trip_id INTEGER NOT NULL REFERENCES trip_budgets(trip_id) ON DELETE CASCADE,
 item_key TEXT NOT NULL,
 label TEXT NOT NULL CHECK(length(label) BETWEEN 1 AND 80),
 category TEXT NOT NULL CHECK(category IN ('交通','住宿','餐饮','门票','购物','其他')),
 planned_cents INTEGER NOT NULL CHECK(planned_cents BETWEEN 0 AND 100000000),
 actual_cents INTEGER NOT NULL CHECK(actual_cents BETWEEN 0 AND 100000000),
 sort_order INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(trip_id,item_key)
);

CREATE TABLE IF NOT EXISTS notifications (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
 path TEXT NOT NULL, seen INTEGER NOT NULL DEFAULT 0 CHECK(seen IN (0,1)),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id,seen,id);
CREATE TABLE IF NOT EXISTS shared_routes (
 source_revision INTEGER NOT NULL DEFAULT 0,
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 source_trip_id INTEGER NOT NULL UNIQUE REFERENCES trips(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id),
 title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
 days INTEGER NOT NULL CHECK(days BETWEEN 1 AND 14),
 status TEXT NOT NULL DEFAULT 'PUBLISHED' CHECK(status IN ('PUBLISHED','PRIVATE')),
 blocked INTEGER NOT NULL DEFAULT 0 CHECK(blocked IN (0,1)), revision INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS shared_route_stops (
 route_id INTEGER NOT NULL REFERENCES shared_routes(id) ON DELETE CASCADE,
 spot_id INTEGER NOT NULL REFERENCES spots(id),day_number INTEGER NOT NULL,sort_order INTEGER NOT NULL,
 PRIMARY KEY(route_id,spot_id)
);
CREATE INDEX IF NOT EXISTS idx_shared_routes_status ON shared_routes(status,blocked,id);
CREATE TABLE IF NOT EXISTS travel_memories (
 trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
 completed_on TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',snapshot TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 1,
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- depth-v2
CREATE TABLE IF NOT EXISTS trip_day_schedules (
  trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
  settings TEXT NOT NULL DEFAULT '{"days":[],"stops":[]}',
  revision INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- Existing databases also need: ALTER TABLE trip_budget_items ADD COLUMN day_number INTEGER NOT NULL DEFAULT 0;
-- ALTER TABLE trip_budget_items ADD COLUMN spot_id INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS trip_meals (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 food_id INTEGER NOT NULL REFERENCES foods(id),
 day_number INTEGER NOT NULL CHECK(day_number BETWEEN 1 AND 14),
 slot TEXT NOT NULL CHECK(slot IN ('早餐','午餐','晚餐','加餐')),
 spot_id INTEGER NOT NULL DEFAULT 0,
 planned_cents INTEGER NOT NULL DEFAULT 0 CHECK(planned_cents BETWEEN 0 AND 100000000),
 notes TEXT NOT NULL DEFAULT '',
 budget_key TEXT NOT NULL UNIQUE,
 revision INTEGER NOT NULL DEFAULT 1,
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 UNIQUE(trip_id,food_id,day_number,slot)
);
CREATE INDEX IF NOT EXISTS idx_trip_meals_trip ON trip_meals(trip_id,day_number,id);

CREATE TABLE IF NOT EXISTS route_favorites (
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 route_id INTEGER NOT NULL REFERENCES shared_routes(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(user_id,route_id)
);
CREATE TABLE IF NOT EXISTS memory_details (
 trip_id INTEGER PRIMARY KEY REFERENCES travel_memories(trip_id) ON DELETE CASCADE,
 entries TEXT NOT NULL DEFAULT '[]', revision INTEGER NOT NULL DEFAULT 1,
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS memory_photos (
 trip_id INTEGER NOT NULL REFERENCES travel_memories(trip_id) ON DELETE CASCADE,
 photo_key TEXT NOT NULL,
 PRIMARY KEY(trip_id,photo_key)
);

-- Persisted private trip history
CREATE TABLE IF NOT EXISTS trip_versions (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 version_number INTEGER NOT NULL CHECK(version_number>0),
 trip_revision INTEGER NOT NULL,
 schedule_revision INTEGER NOT NULL DEFAULT 0,
 kind TEXT NOT NULL CHECK(kind IN ('CREATE','BASELINE','EDIT','TIME','RESTORE')),
 restored_from INTEGER NOT NULL DEFAULT 0,
 summary TEXT NOT NULL,
 snapshot TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 UNIQUE(trip_id,version_number)
);
CREATE INDEX IF NOT EXISTS idx_trip_versions_time ON trip_versions(trip_id,version_number DESC);

-- Constraint planning and idempotent saves
CREATE TABLE IF NOT EXISTS planning_constraints (
 trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
 config TEXT NOT NULL,
 fingerprint TEXT NOT NULL,
 plan_index INTEGER NOT NULL,
 algorithm TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS planner_saves (
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 request_key TEXT NOT NULL,
 payload_hash TEXT NOT NULL,
 trip_id INTEGER REFERENCES trips(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(user_id,request_key)
);

CREATE TRIGGER IF NOT EXISTS notify_social_reply AFTER INSERT ON social_comments
WHEN NEW.user_id<>(SELECT user_id FROM social_posts WHERE id=NEW.post_id)
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path)
 SELECT user_id,'reply','帖子收到新回复',substr(NEW.body,1,120),'/community/post/'||id FROM social_posts WHERE id=NEW.post_id AND status='PUBLISHED';
END;
CREATE TRIGGER IF NOT EXISTS notify_feedback_reply AFTER UPDATE OF status,admin_reply ON feedback
WHEN NEW.admin_reply<>'' AND (NEW.admin_reply<>OLD.admin_reply OR NEW.status<>OLD.status)
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path) VALUES(NEW.user_id,'feedback','意见反馈有了新进展',substr(NEW.admin_reply,1,120),'/feedback');
END;
CREATE TRIGGER IF NOT EXISTS notify_trip_spot_offline AFTER UPDATE OF published ON spots
WHEN OLD.published=1 AND NEW.published=0
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path)
 SELECT t.user_id,'spot','行程中的景点已下架',NEW.title||'：请检查“'||t.title||'”中的安排。','/trip/'||t.id FROM trips t JOIN trip_items i ON i.trip_id=t.id WHERE i.spot_id=NEW.id;
END;


CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(
 title,intro,body,location,type UNINDEXED,item_id UNINDEXED,
 tokenize='unicode61 remove_diacritics 0'
);
CREATE TABLE IF NOT EXISTS search_dirty (
 type TEXT NOT NULL CHECK(type IN ('spot','guide')),
 item_id INTEGER NOT NULL,
 PRIMARY KEY(type,item_id)
);
CREATE TRIGGER IF NOT EXISTS search_spot_insert AFTER INSERT ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_spot_update AFTER UPDATE OF title,intro,description,highlights,transportation,tips,city_id,published ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_spot_delete AFTER DELETE ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',OLD.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_insert AFTER INSERT ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_update AFTER UPDATE OF title,intro,subtitle,sections,city_id,published ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_delete AFTER DELETE ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',OLD.id);
END;
CREATE TRIGGER IF NOT EXISTS search_city_update AFTER UPDATE OF name,province,published ON cities
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'spot',id FROM spots WHERE city_id=NEW.id;
 INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'guide',id FROM guides WHERE city_id=NEW.id;
END;

CREATE UNIQUE INDEX IF NOT EXISTS idx_cities_region_code ON cities(region_code);

-- Stable chronological comment lists
CREATE INDEX IF NOT EXISTS idx_comments_time ON comments(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_social_comments_post_time ON social_comments(post_id,created_at DESC,id DESC);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 actor_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
 actor_name TEXT NOT NULL,
 action TEXT NOT NULL,
 resource TEXT NOT NULL,
 target_id TEXT,
 target_label TEXT NOT NULL DEFAULT '',
 outcome TEXT NOT NULL CHECK(outcome IN ('SUCCESS','FAILED')),
 http_status INTEGER NOT NULL,
 summary TEXT NOT NULL,
 request_method TEXT NOT NULL,
 request_path TEXT NOT NULL,
 request_id TEXT NOT NULL UNIQUE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_time ON admin_audit_logs(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_actor ON admin_audit_logs(actor_name,created_at DESC,id DESC);

-- User contributions, review history, and private-upload ownership registry.
CREATE TABLE IF NOT EXISTS contributions (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 owner_id INTEGER NOT NULL REFERENCES users(id),
 kind TEXT NOT NULL CHECK(kind IN ('spot','guide','food')),
 operation TEXT NOT NULL CHECK(operation IN ('CREATE','UPDATE')),
 status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING','RETURNED','APPROVED')),
 target_id INTEGER,
 target_revision INTEGER,
 base_snapshot TEXT NOT NULL DEFAULT '{}',
 payload TEXT NOT NULL DEFAULT '{}',
 revision INTEGER NOT NULL DEFAULT 1,
 published_id INTEGER,
 review_note TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 submitted_at TEXT,
 reviewed_at TEXT,
 deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS contributions_owner_idx ON contributions(owner_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS contributions_review_idx ON contributions(status,kind,updated_at DESC);
CREATE INDEX IF NOT EXISTS contributions_publication_idx ON contributions(kind,published_id,status,operation,reviewed_at,id);

CREATE TABLE IF NOT EXISTS contribution_reviews (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 contribution_id INTEGER NOT NULL REFERENCES contributions(id) ON DELETE CASCADE,
 decision TEXT NOT NULL CHECK(decision IN ('APPROVED','RETURNED')),
 note TEXT NOT NULL DEFAULT '',
 reviewer_id INTEGER NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS contribution_reviews_item_idx ON contribution_reviews(contribution_id,id);

CREATE TABLE IF NOT EXISTS contribution_images (
 key TEXT PRIMARY KEY,
 owner_id INTEGER NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS admin_audit_publication_idx ON admin_audit_logs(resource,target_id,outcome,action,created_at DESC,id DESC);
