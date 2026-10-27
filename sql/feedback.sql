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
