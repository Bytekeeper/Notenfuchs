package de.notenfuchs.domain;

/**
 * How a Halbjahr average ({@code GradeGridResource}'s H1/H2 columns, never "Jahr" - see
 * {@link SchoolClass#halfYearGradeDisplay}) is displayed to the teacher, on top of the
 * subject's ordinary {@link RoundingMode}-rounded final grade.
 */
public enum HalfYearGradeDisplay {
    /**
     * The existing whole-number final grade, optionally decorated with a +/- tendency suffix
     * (see {@link SchoolClass#halfYearTendencyThresholdPercent}) - never a half-grade.
     */
    WHOLE,

    /**
     * The raw average rounded to the nearest half-grade (e.g. 2.5) instead of a whole number.
     * A tendency suffix never applies here - see
     * {@link de.notenfuchs.service.HalfYearGradeDisplayService} for why stacking a +/- onto an
     * already-finer half-grade step has no sensible meaning.
     */
    HALF;

    /** German display label, used wherever this enum is rendered to a teacher. */
    @Override
    public String toString() {
        return switch (this) {
            case WHOLE -> "Ganze Noten";
            case HALF -> "Halbe Noten";
        };
    }
}
