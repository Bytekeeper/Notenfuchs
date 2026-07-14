package de.notenfuchs.service;

import java.time.LocalDate;

/**
 * Pure, DB-free classification of a single {@code Assessment}'s date into the grade grid's
 * Halbjahr display buckets, given a configurable cutoff date
 * ({@code SchoolClass#halfYearCutoff}). Like {@link GradeService}/{@link PointsConversionService}
 * this is a plain POJO service so the boundary rule is unit-testable without a database - and,
 * crucially, it never computes an average itself: {@code GradeGridResource} feeds the resulting
 * subset of an Assessment's grades into {@link GradeService#calculateSubjectAverage} exactly as
 * before, so grade calculation itself stays completely unaware of Halbjahr (see ROADMAP.md's
 * "Halbjahr as a display filter only").
 */
public class HalfYearAssessmentPartitioner {

    /** Which Halbjahr display bucket an Assessment falls into for a given cutoff. */
    public enum Half {
        /** No date set - counts only into the whole-year ("Jahr") average, never a half. */
        UNDATED,
        /** Dated on or before the cutoff. */
        FIRST,
        /** Dated strictly after the cutoff. */
        SECOND
    }

    /**
     * @param assessmentDate the Assessment's date, or {@code null} if undated
     * @param cutoff         the class's Halbjahr cutoff date (must not be null - callers only
     *                       invoke this once a cutoff is actually configured)
     * @return {@link Half#UNDATED} if {@code assessmentDate} is null, {@link Half#FIRST} if it's
     *         on or before {@code cutoff} (the boundary date itself belongs to the first half),
     *         otherwise {@link Half#SECOND}
     */
    public Half classify(LocalDate assessmentDate, LocalDate cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        if (assessmentDate == null) {
            return Half.UNDATED;
        }
        return assessmentDate.isAfter(cutoff) ? Half.SECOND : Half.FIRST;
    }
}
