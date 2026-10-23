ALTER TABLE track ADD COLUMN kind TEXT NOT NULL DEFAULT 'recording'
    CHECK (kind IN ('recording', 'history'));
ALTER TABLE track ADD COLUMN history_date TEXT
    CHECK ((kind = 'history') = (history_date IS NOT NULL));
CREATE UNIQUE INDEX track_history_date ON track(history_date);
