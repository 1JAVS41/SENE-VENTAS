-- SERVER-SENE additive migration v2: índices para retención segura.
-- No borra ni modifica datos existentes. Ejecutar después de 0001_complete_sync.sql.

CREATE INDEX IF NOT EXISTS idx_payroll_retention
  ON payroll_requests(business_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_reports_retention
  ON report_events(business_id, delivered_at, created_at);
