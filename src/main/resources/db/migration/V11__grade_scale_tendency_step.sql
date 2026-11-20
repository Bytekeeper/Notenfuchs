-- How far "2-" and "2+" lie from the whole grade (0.3: 2- = 2.3, 2+ = 1.7). Per scale, since the
-- step depends on the scale; NULL means the scale accepts no +/- entry.
ALTER TABLE grade_scale ADD COLUMN tendency_step NUMERIC(4,2);
UPDATE grade_scale SET tendency_step = 0.3 WHERE name = 'DE 1-6';
