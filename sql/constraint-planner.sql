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
