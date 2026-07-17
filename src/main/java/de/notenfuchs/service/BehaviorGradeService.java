package de.notenfuchs.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, DB-free computation for the "average per student" figure on the Verhaltensnoten grid
 * (a student's Verhaltensnote averaged across all of their Fächer).
 *
 * <p>Unlike {@link GradeService}, this deliberately does NOT round to a discrete final grade:
 * each Fach may use its own {@link de.notenfuchs.domain.GradeScale}, so a plain cross-subject
 * mean has no single scale to round against. Only the raw numeric average is meaningful here -
 * the discrete final grade is still shown per-Fach (a single scale, via
 * {@link GradeService#calculateAssessmentAverage}), just not for this cross-subject row total.
 */
public class BehaviorGradeService {

    private static final int DISPLAY_SCALE = 2;
    private static final BigDecimal BORDERLINE_LOWER = new BigDecimal("0.4");
    private static final BigDecimal BORDERLINE_UPPER = new BigDecimal("0.6");

    /**
     * Plain arithmetic mean of the given values, rounded to 2 decimal places for display.
     *
     * @return the mean, or {@code null} if values is null/empty (nothing entered yet)
     */
    public BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return sum.divide(new BigDecimal(values.size()), DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * True when {@code average} sits close enough to a whole-number rounding boundary (x.5) that
     * a small change could tip which whole grade it would round to - e.g. 2.4 through 2.6 are all
     * "close to 2.5". Used to flag a student's cross-Fach Verhaltensnote average for a closer look
     * rather than to drive any actual rounding decision (there is no discrete final grade here,
     * see {@link #average(List)}'s javadoc).
     */
    public boolean isBorderline(BigDecimal average) {
        if (average == null) {
            return false;
        }
        BigDecimal floor = average.setScale(0, RoundingMode.FLOOR);
        BigDecimal fraction = average.subtract(floor);
        return fraction.compareTo(BORDERLINE_LOWER) >= 0 && fraction.compareTo(BORDERLINE_UPPER) <= 0;
    }
}
