-- SERVER-SENE additive migration v2: índices para retención segura.
-- No borra ni modifica datos existentes. Ejecutar después de 0001_complete_sync.sql.

CREATE INDEX IF NOT EXISTS idx_payroll_retention
  ON payroll_requests(business_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_reports_retention
  ON report_events(business_id, delivered_at, created_at);

-- ACK de asistencia e inventario marca entrega, no elimina el historial.
-- La limpieza programada conserva los 10 más recientes por categoría y 30 días.
ALTER TABLE attendance_events ADD COLUMN delivered_at TEXT;
ALTER TABLE inventory_events ADD COLUMN delivered_at TEXT;

CREATE INDEX IF NOT EXISTS idx_attendance_retention
  ON attendance_events(business_id, delivered_at, created_at);

CREATE INDEX IF NOT EXISTS idx_inventory_retention
  ON inventory_events(business_id, delivered_at, created_at);
