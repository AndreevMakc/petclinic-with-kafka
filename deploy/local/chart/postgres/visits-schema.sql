CREATE TABLE visits (
  id          SERIAL PRIMARY KEY,
  pet_id      INTEGER NOT NULL,
  visit_date  DATE,
  description VARCHAR(8192)
);

CREATE INDEX visits_pet_id ON visits (pet_id);
