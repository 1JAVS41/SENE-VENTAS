-- SERVER-SENE additive migration v1
-- Ejecutar una sola vez en la D1 existente. No altera ni elimina tablas actuales.

CREATE TABLE IF NOT EXISTS admin_pairing_codes (
  id TEXT PRIMARY KEY,
  business_id TEXT NOT NULL,
  installation_id TEXT NOT NULL,
  code_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  invalidated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_admin_pairing_business
  ON admin_pairing_codes(business_id, expires_at);

CREATE TABLE IF NOT EXISTS admin_sessions (
  id TEXT PRIMARY KEY,
  business_id TEXT NOT NULL UNIQUE,
  admin_installation_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS admin_public_keys (
  business_id TEXT PRIMARY KEY,
  admin_installation_id TEXT NOT NULL,
  public_key TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_requests (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  business_id TEXT NOT NULL,
  worker_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  decision_by TEXT,
  decision_reason TEXT,
  decided_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(business_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_payroll_requests_business_seq
  ON payroll_requests(business_id, seq);

CREATE TABLE IF NOT EXISTS attendance_events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  business_id TEXT NOT NULL,
  worker_id TEXT,
  payload TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(business_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_attendance_business_seq
  ON attendance_events(business_id, seq);

CREATE TABLE IF NOT EXISTS inventory_events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  business_id TEXT NOT NULL,
  worker_id TEXT,
  payload TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(business_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_inventory_business_seq
  ON inventory_events(business_id, seq);

CREATE TABLE IF NOT EXISTS report_events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL,
  business_id TEXT NOT NULL,
  worker_id TEXT,
  envelope TEXT NOT NULL,
  created_at TEXT NOT NULL,
  delivered_at TEXT,
  UNIQUE(business_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_reports_business_seq
  ON report_events(business_id, seq);

CREATE TABLE IF NOT EXISTS server_sync_state (
  business_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  last_seq INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(business_id, channel)
);
