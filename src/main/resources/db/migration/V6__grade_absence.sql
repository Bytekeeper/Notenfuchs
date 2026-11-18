-- An absence mark sits next to a grade and never changes it: a row may now hold only the mark
-- (absent, no grade yet), a grade plus the mark (e.g. written later), or just a grade.
ALTER TABLE grade ADD COLUMN absence VARCHAR(16);
ALTER TABLE grade ADD CONSTRAINT chk_grade_absence CHECK (absence IN ('EXCUSED', 'UNEXCUSED'));
ALTER TABLE grade DROP CONSTRAINT chk_grade_value_xor_points;
ALTER TABLE grade ADD CONSTRAINT chk_grade_value_xor_points
    CHECK (NOT (value IS NOT NULL AND points IS NOT NULL)
           AND (value IS NOT NULL OR points IS NOT NULL OR absence IS NOT NULL));
