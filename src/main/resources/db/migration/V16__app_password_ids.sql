-- App passwords need an id the owner can name when revoking one. Browser sessions leave it null.
ALTER TABLE session ADD COLUMN id TEXT;
CREATE UNIQUE INDEX session_id ON session (id);
