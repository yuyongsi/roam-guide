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
