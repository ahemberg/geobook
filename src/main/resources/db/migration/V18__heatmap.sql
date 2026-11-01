CREATE TABLE heat_entry (
    generation INTEGER NOT NULL,
    cell_x INTEGER NOT NULL,
    cell_y INTEGER NOT NULL,
    local_date TEXT NOT NULL,
    seconds REAL NOT NULL
);
CREATE INDEX heat_entry_generation ON heat_entry(generation);

CREATE TABLE heat_build (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    generation INTEGER,
    built_at TEXT,
    building INTEGER NOT NULL CHECK (building IN (0, 1))
);
INSERT INTO heat_build VALUES (1, NULL, NULL, 0);
