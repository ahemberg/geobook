CREATE TABLE owner (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    username TEXT NOT NULL,
    password_hash TEXT,
    password_changed_at INTEGER,
    totp_secret BLOB,
    recovery_code_hash TEXT,
    two_factor_activated_at INTEGER
);
INSERT INTO owner (singleton, username) VALUES (1, 'admin');

CREATE TABLE session (
    token_hash TEXT PRIMARY KEY CHECK (length(token_hash) = 64),
    kind TEXT NOT NULL CHECK (kind IN ('browser', 'app')),
    name TEXT,
    scope TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_active_at INTEGER NOT NULL,
    expires_at INTEGER,
    second_factor INTEGER NOT NULL DEFAULT 0 CHECK (second_factor IN (0, 1))
) WITHOUT ROWID;
