-- The tendency threshold is now a raw deviance value (a fraction of a whole grade step, e.g.
-- 0.1 for +/-0.1) instead of a percentage - simpler to reason about directly against a raw
-- average than a percentage that first has to be converted to a fraction. Existing values are
-- divided by 100 to preserve their meaning.
ALTER TABLE school_class RENAME COLUMN half_year_tendency_threshold_percent TO half_year_tendency_threshold;
ALTER TABLE school_class ALTER COLUMN half_year_tendency_threshold TYPE NUMERIC(3,2) USING (half_year_tendency_threshold::numeric / 100);
