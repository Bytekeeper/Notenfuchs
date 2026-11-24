package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, DB-free logic of a points Leistung's Notenschlüssel: rows of points and grade, read as
 * steps ("ab 35 Punkten eine 1") or sliding. Callers convert on every read and never store the
 * grade, so changing the points or the rows changes the grade at once.
 */
public class PointsConversionService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIFTY = new BigDecimal("50");
    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * As steps: the grade of the best row the points reach, below every row the scale's worst grade.
     * Sliding: the grade moves evenly between the two rows around the points and continues the
     * outer rows' line beyond them, stopping at the scale's ends; rounded to one decimal in the
     * student's favor.
     */
    public BigDecimal convert(BigDecimal points, List<PointsGradeBandData> rows, GradeScale scale, boolean sliding) {
        if (sliding && rows.size() >= 2) {
            return slide(points, rows, scale);
        }
        BigDecimal grade = scale.lowerIsBetter ? scale.max : scale.min;
        for (PointsGradeBandData row : rows) {
            if (points.compareTo(row.points()) >= 0 && isBetter(row.gradeValue(), grade, scale)) {
                grade = row.gradeValue();
            }
        }
        return grade;
    }

    private static BigDecimal slide(BigDecimal points, List<PointsGradeBandData> rows, GradeScale scale) {
        List<PointsGradeBandData> byPoints = new ArrayList<>(rows);
        byPoints.sort(Comparator.comparing(PointsGradeBandData::points));
        int lower = 0;
        while (lower < byPoints.size() - 2 && points.compareTo(byPoints.get(lower + 1).points()) >= 0) {
            lower++;
        }
        PointsGradeBandData a = byPoints.get(lower);
        PointsGradeBandData b = byPoints.get(lower + 1);
        BigDecimal span = b.points().subtract(a.points());
        if (span.signum() == 0) {
            // Only old keys can have two rows at the same points; the dialog refuses it.
            return a.gradeValue();
        }
        BigDecimal fraction = points.subtract(a.points()).divide(span, MathContext.DECIMAL64);
        BigDecimal grade = a.gradeValue().add(fraction.multiply(b.gradeValue().subtract(a.gradeValue())));
        grade = grade.max(scale.min).min(scale.max);
        return grade.setScale(1, scale.lowerIsBetter ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    /** True if a better grade never needs fewer or the same points as a worse one. */
    public boolean isOrdered(List<PointsGradeBandData> steps, GradeScale scale) {
        List<PointsGradeBandData> byPoints = new ArrayList<>(steps);
        byPoints.sort(Comparator.comparing(PointsGradeBandData::points));
        for (int i = 1; i < byPoints.size(); i++) {
            PointsGradeBandData lower = byPoints.get(i - 1);
            PointsGradeBandData higher = byPoints.get(i);
            if (higher.points().compareTo(lower.points()) == 0 || !isBetter(higher.gradeValue(), lower.gradeValue(), scale)) {
                return false;
            }
        }
        return true;
    }

    /** Steps in points for a test, rounded down to half points so rounding never makes a grade harder to get. */
    public List<PointsGradeBandData> stepsFromKey(BigDecimal maxPoints, List<PercentStepData> key) {
        List<PointsGradeBandData> steps = new ArrayList<>();
        for (PercentStepData row : key) {
            BigDecimal points = maxPoints.multiply(row.minPercent()).divide(HUNDRED)
                    .multiply(TWO).setScale(0, RoundingMode.FLOOR).divide(TWO);
            steps.add(new PointsGradeBandData(points, row.gradeValue()));
        }
        return steps;
    }

    /**
     * A Fach's key until the teacher saves one, read sliding: the best grade at 100 % and the
     * middle of the scale at 50 %, i.e. one straight line from the best grade at full points to the
     * worst at none.
     */
    public List<PercentStepData> defaultKey(GradeScale scale) {
        BigDecimal best = scale.lowerIsBetter ? scale.min : scale.max;
        BigDecimal middle = scale.min.add(scale.max).divide(TWO);
        return List.of(new PercentStepData(best, HUNDRED), new PercentStepData(middle, FIFTY));
    }

    private static boolean isBetter(BigDecimal a, BigDecimal b, GradeScale scale) {
        int comparison = a.compareTo(b);
        return scale.lowerIsBetter ? comparison < 0 : comparison > 0;
    }
}
