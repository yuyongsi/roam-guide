CREATE TABLE IF NOT EXISTS contributions (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 owner_id INTEGER NOT NULL REFERENCES users(id),
 kind TEXT NOT NULL CHECK(kind IN ('spot','guide','food')),
 operation TEXT NOT NULL CHECK(operation IN ('CREATE','UPDATE')),
 status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING','RETURNED','APPROVED')),
 target_id INTEGER,
 target_revision INTEGER,
 base_snapshot TEXT NOT NULL DEFAULT '{}',
 payload TEXT NOT NULL DEFAULT '{}',
 revision INTEGER NOT NULL DEFAULT 1,
 published_id INTEGER,
 review_note TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 submitted_at TEXT,
 reviewed_at TEXT,
 deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS contributions_owner_idx ON contributions(owner_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS contributions_review_idx ON contributions(status,kind,updated_at DESC);
CREATE INDEX IF NOT EXISTS contributions_publication_idx ON contributions(kind,published_id,status,operation,reviewed_at,id);
CREATE TABLE IF NOT EXISTS contribution_reviews (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 contribution_id INTEGER NOT NULL REFERENCES contributions(id) ON DELETE CASCADE,
 decision TEXT NOT NULL CHECK(decision IN ('APPROVED','RETURNED')),
 note TEXT NOT NULL DEFAULT '',
 reviewer_id INTEGER NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS contribution_reviews_item_idx ON contribution_reviews(contribution_id,id);
CREATE TABLE IF NOT EXISTS contribution_images (
 key TEXT PRIMARY KEY,
 owner_id INTEGER NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS admin_audit_publication_idx ON admin_audit_logs(resource,target_id,outcome,action,created_at DESC,id DESC);
