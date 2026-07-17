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
 * <p>{@link HalfYearGradeDisplay#WHOLE} shows the existing {@code finalGrade}, optionally
 * decorated with a +/- tendency suffix. {@link HalfYearGradeDisplay#HALF} shows the raw average
 * rounded to the nearest half-grade instead - and never gets a tendency suffix: no German
 * Land's regulations stack a tendency onto a half-grade (every "Notentendenz" permission we
 * found is phrased as a *ganze* Note with tendency), and mechanically it would produce nonsense
 * like "3.5+" for a value that's numerically *better* than 3.5 - see the design discussion this
 * service resulted from. {@link #label} therefore ignores the threshold entirely whenever mode
 * isn't {@code WHOLE}, so that combination is structurally impossible regardless of what's
 * stored (the class settings form additionally forces the threshold back to {@code null} the
 * moment {@code HALF} is selected - see {@code ClassUiResource#updateHalfYearGradeDisplay}).
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
     *                                {@link GradeService} exactly as for any other average)
     * @param mode                    whole or half-grade display
     * @param tendencyThresholdPercent width (0-49) of the "plain" zone around a whole grade
     *                                 within which no tendency suffix is shown; {@code null}
     *                                 disables the suffix. Only consulted when {@code mode} is
     *                                 {@link HalfYearGradeDisplay#WHOLE}.
     * @param scale                   the subject's grade scale, consulted for {@code lowerIsBetter}
     */
    public String label(BigDecimal rawAverage, Integer finalGrade, HalfYearGradeDisplay mode,
                         Integer tendencyThresholdPercent, GradeScale scale) {
        if (rawAverage == null || finalGrade == null) {
            return null;
        }
        if (mode == HalfYearGradeDisplay.HALF) {
            return plain(roundToHalf(rawAverage));
        }
        if (tendencyThresholdPercent == null) {
            return String.valueOf(finalGrade);
        }
        return finalGrade + tendencySuffix(rawAverage, finalGrade, tendencyThresholdPercent, scale);
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
        BigDecimal thresholdFraction = new BigDecimal(thresholdPercent).divide(HUNDRED);
        if (deviation.abs().compareTo(thresholdFraction) <= 0) {
            return "";
        }
        boolean towardLowerNeighbor = deviation.signum() < 0;
        boolean towardBetterNeighbor = towardLowerNeighbor == scale.lowerIsBetter;
        return towardBetterNeighbor ? "+" : "-";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
