CREATE TABLE IF NOT EXISTS visit_notifications (
  event_id VARCHAR(36) PRIMARY KEY,
  visit_id INTEGER NOT NULL,
  pet_id INTEGER NOT NULL,
  visit_date DATE NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS visit_notifications_pet ON visit_notifications(pet_id);
GRANT SELECT ON visit_notifications TO datadog;
