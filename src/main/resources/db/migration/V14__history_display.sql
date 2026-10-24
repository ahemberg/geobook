CREATE TABLE history_display (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    show_history INTEGER NOT NULL CHECK (show_history IN (0, 1))
);
INSERT INTO history_display VALUES (1, 1);

CREATE TABLE history_hidden_year (
    year INTEGER PRIMARY KEY CHECK (year BETWEEN 1 AND 9999)
);
