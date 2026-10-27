CREATE TABLE IF NOT EXISTS trip_day_schedules (
  trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
  settings TEXT NOT NULL DEFAULT '{"days":[],"stops":[]}',
  revision INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
