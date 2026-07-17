-- How the Halbjahr split view's H1/H2 average columns are displayed: whole grades (default,
-- unchanged behavior) or half-grades, plus an optional +/- tendency band width (only meaningful
-- for whole grades - see SchoolClass.halfYearTendencyThresholdPercent). "Jahr" always stays a
-- plain whole grade regardless of this setting.
ALTER TABLE school_class
    ADD COLUMN half_year_grade_display VARCHAR(10) NOT NULL DEFAULT 'WHOLE',
    ADD COLUMN half_year_tendency_threshold_percent INTEGER;
