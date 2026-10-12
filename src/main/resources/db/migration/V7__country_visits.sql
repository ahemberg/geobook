ALTER TABLE track_point ADD COLUMN country_code TEXT;
ALTER TABLE track_point ADD COLUMN country_version TEXT;
CREATE INDEX track_point_country_day ON track_point(country_code, local_date, track_id);
CREATE TABLE visit_review (
    country_code TEXT NOT NULL,
    local_date TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('pending', 'confirmed', 'excluded')),
    PRIMARY KEY (country_code, local_date)
);
