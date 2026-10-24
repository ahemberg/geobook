ALTER TABLE track ADD COLUMN kind TEXT NOT NULL DEFAULT 'recording'
    CHECK (kind IN ('recording', 'history'));
ALTER TABLE track ADD COLUMN history_date TEXT
    CHECK ((kind = 'recording' AND history_date IS NULL)
        OR (kind = 'history' AND history_date IS NOT NULL));
CREATE UNIQUE INDEX history_day_date ON track(history_date) WHERE kind = 'history';
