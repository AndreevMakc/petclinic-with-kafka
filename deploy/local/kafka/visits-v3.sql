CREATE TABLE IF NOT EXISTS visit_outbox (
  event_id VARCHAR(36) PRIMARY KEY,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS visit_outbox_created ON visit_outbox(created_at);
GRANT SELECT ON visit_outbox TO datadog;
