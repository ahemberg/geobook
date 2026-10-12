ALTER TABLE track ADD COLUMN visible INTEGER NOT NULL DEFAULT 1 CHECK (visible IN (0, 1));
ALTER TABLE track ADD COLUMN south REAL;
ALTER TABLE track ADD COLUMN north REAL;
ALTER TABLE track ADD COLUMN west REAL;
ALTER TABLE track ADD COLUMN east REAL;
UPDATE track SET
    south = (SELECT min(CAST(latitude AS REAL)) FROM track_point WHERE track_id = track.id),
    north = (SELECT max(CAST(latitude AS REAL)) FROM track_point WHERE track_id = track.id),
    west = (SELECT min(CAST(longitude AS REAL)) FROM track_point WHERE track_id = track.id),
    east = (SELECT max(CAST(longitude AS REAL)) FROM track_point WHERE track_id = track.id);
