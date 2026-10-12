ALTER TABLE track ADD COLUMN start_time TEXT;
CREATE TABLE overview_run (
    track_id INTEGER NOT NULL REFERENCES track(id) ON DELETE CASCADE,
    first_ordinal INTEGER NOT NULL,
    last_ordinal INTEGER NOT NULL,
    local_date TEXT,
    break_before INTEGER NOT NULL,
    seconds_before INTEGER NOT NULL,
    PRIMARY KEY (track_id, first_ordinal)
);
CREATE INDEX overview_run_local_date ON overview_run(local_date, track_id, first_ordinal);
CREATE TABLE overview_point (
    track_id INTEGER NOT NULL,
    run_ordinal INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    latitude TEXT NOT NULL,
    longitude TEXT NOT NULL,
    PRIMARY KEY (track_id, run_ordinal, ordinal),
    FOREIGN KEY (track_id, run_ordinal) REFERENCES overview_run(track_id, first_ordinal) ON DELETE CASCADE
);
