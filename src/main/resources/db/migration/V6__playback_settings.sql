CREATE TABLE playback_settings (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    gap_seconds INTEGER NOT NULL CHECK (gap_seconds > 0),
    show_gap_connections INTEGER NOT NULL CHECK (show_gap_connections IN (0, 1))
);
INSERT INTO playback_settings VALUES (1, 300, 0);
