-- Halbjahr (half-year) split view for the grade grid: a nullable cutoff date on the class.
-- Purely a display/query filter (see ROADMAP.md's "Halbjahr as a display filter only") - no new
-- grade entities, no snapshots. When null, the grade grid renders exactly as before (single
-- full-year view). When set, Assessments dated on/before the cutoff count into "1. Halbjahr",
-- those after into "2. Halbjahr"; the whole-year average is unaffected either way.
ALTER TABLE school_class
    ADD COLUMN half_year_cutoff DATE;
