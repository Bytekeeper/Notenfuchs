package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * "Was brauche ich für eine 2?": for one more Leistung in a given category, the grade a student
 * needs to reach each final grade. Instead of solving the weighting formula backwards, it tries
 * every possible grade (in steps of 0.1) through {@link GradeService}, so the answer follows
 * exactly the same weighting, empty-category and rounding rules as every average in the app.
 * Pure and DB-free like {@link GradeService}.
 */
public class RequiredGradeService {

    private static final BigDecimal STEP = new BigDecimal("0.1");

    private final GradeService gradeService = new GradeService();

    /**
     * @param requiredGrade the worst grade that still reaches {@code targetGrade}; null when
     *                      {@code unreachable} or {@code alreadySafe}
     * @param alreadySafe   even the scale's worst grade reaches the target
     * @param unreachable   not even the scale's best grade reaches the target
     */
    public record Requirement(int targetGrade, BigDecimal requiredGrade, boolean alreadySafe, boolean unreachable) {
    }

    /**
     * @param categories          the student's current grades, one entry per category of the Fach
     * @param targetCategoryIndex the category the next Leistung belongs to
     * @param factor              that Leistung's factor
     * @return one entry per whole grade of the scale, best first
     */
    public List<Requirement> requiredGrades(List<CategoryData> categories, int targetCategoryIndex, BigDecimal factor,
                                            GradeScale scale, RoundingMode roundingMode) {
        BigDecimal worst = scale.lowerIsBetter ? scale.max : scale.min;
        BigDecimal best = scale.lowerIsBetter ? scale.min : scale.max;
        BigDecimal towardBest = scale.lowerIsBetter ? STEP.negate() : STEP;

        // Candidate grades from worst to best, each with the final grade it would lead to.
        List<BigDecimal> candidates = new ArrayList<>();
        List<Integer> finals = new ArrayList<>();
        int steps = scale.max.subtract(scale.min).divide(STEP).intValue();
        for (int i = 0; i <= steps; i++) {
            BigDecimal x = worst.add(towardBest.multiply(BigDecimal.valueOf(i)));
            candidates.add(x);
            finals.add(finalGradeWith(categories, targetCategoryIndex, new GradeData(x, factor), scale, roundingMode));
        }

        List<Requirement> result = new ArrayList<>();
        int bestWhole = best.intValue();
        int worstWhole = worst.intValue();
        int step = scale.lowerIsBetter ? 1 : -1;
        for (int target = bestWhole; target != worstWhole + step; target += step) {
            int firstReaching = -1;
            for (int i = 0; i < candidates.size(); i++) {
                if (isAtLeast(BigDecimal.valueOf(finals.get(i)), BigDecimal.valueOf(target), scale)) {
                    firstReaching = i;
                    break;
                }
            }
            if (firstReaching < 0) {
                result.add(new Requirement(target, null, false, true));
            } else if (firstReaching == 0) {
                result.add(new Requirement(target, null, true, false));
            } else {
                result.add(new Requirement(target, candidates.get(firstReaching), false, false));
            }
        }
        return result;
    }

    private Integer finalGradeWith(List<CategoryData> categories, int targetCategoryIndex, GradeData extra,
                                   GradeScale scale, RoundingMode roundingMode) {
        List<CategoryData> withExtra = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            CategoryData category = categories.get(i);
            if (i == targetCategoryIndex) {
                List<GradeData> grades = new ArrayList<>(category.grades());
                grades.add(extra);
                category = new CategoryData(category.weightPercent(), grades);
            }
            withExtra.add(category);
        }
        return gradeService.calculateSubjectAverage(withExtra, scale, roundingMode).finalGrade();
    }

    /** True if {@code a} is as good as {@code b} or better on this scale. */
    private static boolean isAtLeast(BigDecimal a, BigDecimal b, GradeScale scale) {
        int comparison = a.compareTo(b);
        return scale.lowerIsBetter ? comparison <= 0 : comparison >= 0;
    }
}
