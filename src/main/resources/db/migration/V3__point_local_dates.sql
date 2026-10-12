ALTER TABLE track_point ADD COLUMN timezone TEXT;
ALTER TABLE track_point ADD COLUMN local_date TEXT;
ALTER TABLE track_point ADD COLUMN local_time TEXT;
ALTER TABLE track_point ADD COLUMN time_status TEXT;
ALTER TABLE track_point ADD COLUMN boundary_version TEXT;
ALTER TABLE track_point ADD COLUMN tzdb_version TEXT;
CREATE INDEX track_point_local_date ON track_point(local_date, track_id, ordinal);
