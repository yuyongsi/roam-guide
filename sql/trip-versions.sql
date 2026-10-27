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
