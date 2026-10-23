-- Hibernate maps Visit.date to TIMESTAMP; converting DATE preserves existing dates.
ALTER TABLE visits ALTER COLUMN visit_date TYPE TIMESTAMP(6)
  USING visit_date::TIMESTAMP(6);
