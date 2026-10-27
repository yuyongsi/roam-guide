CREATE TABLE IF NOT EXISTS comment_likes (
 comment_id INTEGER NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY(comment_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_comment_likes_user ON comment_likes(user_id);
