CREATE TABLE original_export (
    id INTEGER PRIMARY KEY,
    filename TEXT NOT NULL,
    bytes BLOB NOT NULL
);
CREATE TABLE track (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    colour TEXT NOT NULL DEFAULT '#ad466a'
);
CREATE TABLE track_original (
    track_id INTEGER NOT NULL REFERENCES track(id),
    original_id INTEGER NOT NULL REFERENCES original_export(id),
    PRIMARY KEY (track_id, original_id)
);
CREATE TABLE track_segment (
    track_id INTEGER NOT NULL REFERENCES track(id),
    ordinal INTEGER NOT NULL,
    PRIMARY KEY (track_id, ordinal)
);
CREATE TABLE track_point (
    track_id INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    segment INTEGER NOT NULL,
    latitude TEXT NOT NULL,
    longitude TEXT NOT NULL,
    elevation TEXT,
    recorded_time TEXT,
    PRIMARY KEY (track_id, ordinal),
    FOREIGN KEY (track_id, segment) REFERENCES track_segment(track_id, ordinal)
);
