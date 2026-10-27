CREATE TABLE IF NOT EXISTS trip_budgets (
 trip_id INTEGER PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
 people INTEGER NOT NULL DEFAULT 1 CHECK(people BETWEEN 1 AND 99),
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>=1),
 notes TEXT NOT NULL DEFAULT '',
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS trip_budget_items (
 trip_id INTEGER NOT NULL REFERENCES trip_budgets(trip_id) ON DELETE CASCADE,
 item_key TEXT NOT NULL,
 label TEXT NOT NULL CHECK(length(label) BETWEEN 1 AND 80),
 category TEXT NOT NULL CHECK(category IN ('交通','住宿','餐饮','门票','购物','其他')),
 planned_cents INTEGER NOT NULL CHECK(planned_cents BETWEEN 0 AND 100000000),
 actual_cents INTEGER NOT NULL CHECK(actual_cents BETWEEN 0 AND 100000000),
 sort_order INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(trip_id,item_key)
);
