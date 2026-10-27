CREATE TABLE IF NOT EXISTS notifications (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
 path TEXT NOT NULL, seen INTEGER NOT NULL DEFAULT 0 CHECK(seen IN (0,1)),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id,seen,id);
CREATE TABLE IF NOT EXISTS shared_routes (
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
