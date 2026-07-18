-- min_points implied a bracket floor ("at or above this many points, use this grade"), which
-- stopped being accurate once PointsConversionService started extrapolating linearly beyond a
-- band's threshold instead of clamping at it - each row is really just one (points, grade)
-- anchor point on that line, not the floor of a range.
ALTER TABLE points_grade_band RENAME COLUMN min_points TO points;
