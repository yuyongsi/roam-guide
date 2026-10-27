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
