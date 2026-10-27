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
