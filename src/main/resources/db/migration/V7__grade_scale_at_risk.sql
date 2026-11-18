-- The final grade from which a student counts as at risk (e.g. 5 on DE 1-6). Per scale, since
-- "how bad is failing" differs between scales; NULL means the scale shows no at-risk signal.
ALTER TABLE grade_scale ADD COLUMN at_risk_from NUMERIC(4,2);
UPDATE grade_scale SET at_risk_from = 5 WHERE name = 'DE 1-6';
