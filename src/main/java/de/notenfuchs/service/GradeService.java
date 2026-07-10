package de.notenfuchs.service;

import de.notenfuchs.domain.RoundingMode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Pure, DB-free computation service for per-student, per-subject grade averages.
 *
 * This class operates only on plain in-memory data ({@link CategoryData}, {@link GradeData})
 * so it can be unit tested without a running database. A REST-facing service is expected
 * to load Panache entities, convert them into these DTOs, call this service, and map the
 * result back to a response DTO.
 *
 * The calculation logic never hardcodes any specific grading scale (e.g. the German 1-6
 * scale) - it is entirely scale-agnostic and driven by the {@code lowerIsBetter} flag
 * passed in for rounding decisions.
 */
public class GradeService {

    private static final MathContext CALC_CONTEXT = MathContext.DECIMAL64;
    private static final int DISPLAY_SCALE = 2;
    private static final BigDecimal ONE_HALF = new BigDecimal("0.5");

    /**
     * Computes the weighted mean of a single category's grades for one student.
     * Each grade is weighted by its assessment's factor:
     * {@code sum(value_i * factor_i) / sum(factor_i)}.
     *
     * @param grades the student's grades within the category (must not be empty)
     * @return the weighted category average, at DECIMAL64 precision (not yet rounded for display)
     * @throws IllegalArgumentException if grades is empty or the sum of factors is zero
     */
    public BigDecimal calculateCategoryAverage(List<GradeData> grades) {
        if (grades == null || grades.isEmpty()) {
            throw new IllegalArgumentException("grades must not be empty");
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal factorSum = BigDecimal.ZERO;
        for (GradeData grade : grades) {
            weightedSum = weightedSum.add(grade.value().multiply(grade.factor(), CALC_CONTEXT), CALC_CONTEXT);
            factorSum = factorSum.add(grade.factor(), CALC_CONTEXT);
        }
        if (factorSum.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("sum of factors must not be zero");
        }
        return weightedSum.divide(factorSum, CALC_CONTEXT);
    }

    /**
     * Computes the weighted subject average for one student across all categories,
     * combining category averages using each category's weightPercent - but normalized
     * over the sum of weightPercent of ONLY the categories that actually have at least
     * one grade for this student. Categories with no grades are excluded entirely so
     * they don't distort the result (e.g. an empty 50%-weight category doesn't drag the
     * average toward zero; the populated category effectively becomes 100% weight).
     *
     * @param categories all categories of the subject, each with this student's grades (may be empty per category)
     * @return the result containing the raw average (rounded to 2dp for display) and the
     *         final (rounded whole-number) grade, or {@link SubjectAverageResult#EMPTY} if
     *         no category has any grades at all
     */
    public SubjectAverageResult calculateSubjectAverage(List<CategoryData> categories,
                                                          de.notenfuchs.domain.GradeScale scale,
                                                          RoundingMode roundingMode) {
        if (categories == null) {
            categories = List.of();
        }

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;

        for (CategoryData category : categories) {
            if (!category.hasGrades()) {
                continue;
            }
            BigDecimal categoryAverage = calculateCategoryAverage(category.grades());
            weightedSum = weightedSum.add(categoryAverage.multiply(category.weightPercent(), CALC_CONTEXT), CALC_CONTEXT);
            weightSum = weightSum.add(category.weightPercent(), CALC_CONTEXT);
        }

        if (weightSum.compareTo(BigDecimal.ZERO) == 0) {
            return SubjectAverageResult.EMPTY;
        }

        BigDecimal rawAveragePrecise = weightedSum.divide(weightSum, CALC_CONTEXT);
        BigDecimal rawAverageDisplay = rawAveragePrecise.setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
        int finalGrade = round(rawAveragePrecise, roundingMode, scale.lowerIsBetter);

        return new SubjectAverageResult(rawAverageDisplay, finalGrade);
    }

    /**
     * Rounds a raw (decimal) average to a whole number final grade according to the given
     * rounding mode.
     *
     * <ul>
     *   <li>{@link RoundingMode#COMMERCIAL}: standard numeric half-up rounding. x.50 always
     *       rounds UP to the next higher whole number, regardless of the scale's
     *       lowerIsBetter flag (purely numeric "kaufmaennisch" rounding).</li>
     *   <li>{@link RoundingMode#IN_FAVOR_OF_STUDENT}: identical to COMMERCIAL except for
     *       values exactly at a half (x.50), which round toward whichever whole number is
     *       better for the student per {@code lowerIsBetter}: toward the lower number if
     *       lowerIsBetter is true, toward the higher number if lowerIsBetter is false.
     *       Non-half values round to the nearest whole number the same way in both modes.</li>
     * </ul>
     *
     * @param rawAverage    the precise (unrounded-for-display) raw average
     * @param roundingMode  which rounding policy to apply
     * @param lowerIsBetter whether a numerically lower value is the better grade on this scale
     * @return the rounded whole-number final grade
     */
    public int round(BigDecimal rawAverage, RoundingMode roundingMode, boolean lowerIsBetter) {
        BigDecimal floor = rawAverage.setScale(0, java.math.RoundingMode.FLOOR);
        BigDecimal fraction = rawAverage.subtract(floor);

        boolean isExactHalf = fraction.compareTo(ONE_HALF) == 0;

        if (isExactHalf && roundingMode == RoundingMode.IN_FAVOR_OF_STUDENT) {
            BigDecimal lower = floor;
            BigDecimal higher = floor.add(BigDecimal.ONE);
            BigDecimal better = lowerIsBetter ? lower : higher;
            return better.intValueExact();
        }

        // COMMERCIAL, or IN_FAVOR_OF_STUDENT on a non-half value: standard half-up rounding.
        return rawAverage.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
    }
}
