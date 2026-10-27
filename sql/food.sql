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
