ALTER TABLE track ADD COLUMN kept_fix_count INTEGER CHECK (kept_fix_count >= 0);
ALTER TABLE track ADD COLUMN dropped_fix_count INTEGER CHECK (dropped_fix_count >= 0);

UPDATE track
SET kept_fix_count = (SELECT count(*) FROM track_point WHERE track_id = track.id),
    dropped_fix_count = 0
WHERE kind = 'history';
