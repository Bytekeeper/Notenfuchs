package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.HalfYearGradeDisplay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, DB-free formatting of a Halbjahr average (H1/H2 column - never "Jahr", which always
 * stays a plain whole grade) into what the teacher actually sees, per
 * {@link de.notenfuchs.domain.SchoolClass#halfYearGradeDisplay}. Like {@link GradeService}, this
 * never changes the underlying grade calculation - {@code GradeGridResource} still computes
 * {@code rawAverage} and the {@code finalGrade} (rounded per the subject's ordinary
 * {@link de.notenfuchs.domain.RoundingMode}) exactly as before; this service only decides how
 * that pair is turned into a display string.
 *
 * <p>{@link HalfYearGradeDisplay#WHOLE} shows {@code finalGrade}, optionally decorated with a
 * +/- tendency suffix (see {@link #tendencySuffix}) once {@code rawAverage} is far enough from
 * it (per {@code tendencyThresholdPercent}, a percentage of a whole grade step). It never shows
 * a half-grade.
 *
 * <p>{@link HalfYearGradeDisplay#HALF} shows the raw average rounded to the nearest half-grade
 * ({@link #roundToHalf}) when no tendency threshold is configured - exactly as {@code WHOLE}
 * without a threshold shows a bare {@code finalGrade}. With a threshold configured, {@code HALF}
 * reuses the exact same whole-grade tendency computation as {@code WHOLE} (same anchor
 * {@code finalGrade}, same threshold, same suffix direction) and then applies one refinement:
 * if {@code rawAverage} would get a suffix (i.e. it's outside {@code finalGrade}'s plain zone)
 * <em>and</em> it is also within that same threshold of the neighboring half-grade the suffix is
 * pointing toward, that half-grade is shown bare instead of "finalGrade+suffix" - e.g. 2.6 is
 * outside whole grade 3's plain zone (leaning "+"/better) but close enough to the half-grade 2.5
 * that "2.5" is the more honest label than "3+". This is exactly the German "ganze Note",
 * "ganze Note mit Tendenz" and "halbe Note" report-card conventions (e.g. Baden-Württemberg's
 * NVO, Berlin's AV-Zeugnisse) collapsed into one continuous scale: half-grade territory is
 * simply the finer resolution you reach once a whole-grade tendency would otherwise apply.
 */
public class HalfYearGradeDisplayService {

    private static final BigDecimal HALF_STEP = new BigDecimal("0.5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * The display label for one Halbjahr average cell, or {@code null} if the student has no
     * grades in that half yet ({@code rawAverage}/{@code finalGrade} both null - the caller's
     * usual "empty" signal, mirroring {@link SubjectAverageResult#EMPTY}).
     *
     * @param rawAverage              the half's precise raw average
     * @param finalGrade              the half's already-rounded whole grade (per the subject's
     *                                {@link de.notenfuchs.domain.RoundingMode}, computed by
     *                                {@link GradeService} exactly as for any other average) -
     *                                the anchor both modes' tendency suffix is computed against
     * @param mode                    whole or half-grade display
     * @param tendencyThresholdPercent width (0-49) of the "plain" zone around a whole grade
     *                                 within which no tendency suffix is shown; {@code null}
     *                                 disables the suffix entirely (in {@code HALF}, that means
     *                                 always showing a bare half-grade, never a suffix).
     * @param scale                   the subject's grade scale, consulted for {@code lowerIsBetter}
     */
    public String label(BigDecimal rawAverage, Integer finalGrade, HalfYearGradeDisplay mode,
                         Integer tendencyThresholdPercent, GradeScale scale) {
        if (rawAverage == null || finalGrade == null) {
            return null;
        }
        if (tendencyThresholdPercent == null) {
            return mode == HalfYearGradeDisplay.HALF
                    ? plain(roundToHalf(rawAverage))
                    : String.valueOf(finalGrade);
        }
        String suffix = tendencySuffix(rawAverage, finalGrade, tendencyThresholdPercent, scale);
        if (suffix.isEmpty()) {
            return String.valueOf(finalGrade);
        }
        if (mode == HalfYearGradeDisplay.HALF) {
            BigDecimal neighborHalfGrade = neighborHalfGrade(rawAverage, finalGrade);
            BigDecimal thresholdFraction = thresholdFraction(tendencyThresholdPercent);
            if (rawAverage.subtract(neighborHalfGrade).abs().compareTo(thresholdFraction) <= 0) {
                return plain(neighborHalfGrade);
            }
        }
        return finalGrade + suffix;
    }

    /** Rounds to the nearest half-grade (e.g. 2.24 -> 2.0, 2.25 -> 2.5), ties rounding up. */
    public BigDecimal roundToHalf(BigDecimal rawAverage) {
        BigDecimal steps = rawAverage.divide(HALF_STEP, 0, RoundingMode.HALF_UP);
        return steps.multiply(HALF_STEP);
    }

    /**
     * The +/- tendency suffix for a raw average against its already-rounded whole grade: empty
     * once {@code rawAverage} is within {@code thresholdPercent}% of a whole grade step of
     * {@code finalGrade} (the "plain" zone - deliberately symmetric on both sides, since unlike
     * a single Land's own convention, e.g. Baden-Württemberg's NVO itself only names a
     * discretionary "Grenzbereich" without codifying an exact bracket table); otherwise "+" when
     * leaning toward the numerically better neighboring grade (per {@code scale.lowerIsBetter})
     * or "-" when leaning toward the worse one.
     *
     * @param finalGrade the whole grade {@code rawAverage} was already rounded to - the anchor
     *                   the suffix is computed relative to, not a fresh independent rounding
     */
    public String tendencySuffix(BigDecimal rawAverage, int finalGrade, int thresholdPercent, GradeScale scale) {
        BigDecimal deviation = rawAverage.subtract(new BigDecimal(finalGrade));
        if (deviation.abs().compareTo(thresholdFraction(thresholdPercent)) <= 0) {
            return "";
        }
        boolean towardLowerNeighbor = deviation.signum() < 0;
        boolean towardBetterNeighbor = towardLowerNeighbor == scale.lowerIsBetter;
        return towardBetterNeighbor ? "+" : "-";
    }

    /**
     * The half-grade adjacent to {@code finalGrade} in the numeric direction {@code rawAverage}
     * deviates toward - e.g. finalGrade 2 with rawAverage 2.2 (above) gives 2.5; finalGrade 3
     * with rawAverage 2.6 (below) gives 2.5 too. Only meaningful once {@link #tendencySuffix} has
     * already established {@code rawAverage} isn't in {@code finalGrade}'s own plain zone -
     * i.e. the deviation is non-zero, so a direction actually exists.
     */
    private BigDecimal neighborHalfGrade(BigDecimal rawAverage, int finalGrade) {
        BigDecimal anchor = new BigDecimal(finalGrade);
        return rawAverage.compareTo(anchor) > 0 ? anchor.add(HALF_STEP) : anchor.subtract(HALF_STEP);
    }

    private static BigDecimal thresholdFraction(int thresholdPercent) {
        return new BigDecimal(thresholdPercent).divide(HUNDRED);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
