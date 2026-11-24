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
     * A Fach's key until the teacher saves one: the pass grade (the one before {@code atRiskFrom})
     * at 50 %, the better grades evenly up to 100 %, the worse ones evenly down to 0 %. Without
     * {@code atRiskFrom} all grades share 100 % evenly. The worst grade has no row.
     */
    public List<PercentStepData> defaultKey(GradeScale scale) {
        List<Integer> grades = scale.wholeGradesBestFirst();
        int worst = grades.size() - 1;
        int pass = scale.atRiskFrom == null ? -1 : grades.indexOf(scale.atRiskFrom.intValue()) - 1;
        List<PercentStepData> key = new ArrayList<>();
        for (int i = 0; i < worst; i++) {
            BigDecimal percent;
            if (pass < 0) {
                percent = HUNDRED.multiply(BigDecimal.valueOf(worst - i)).divide(BigDecimal.valueOf(worst + 1), 1, RoundingMode.HALF_UP);
            } else if (i <= pass) {
                percent = FIFTY.add(FIFTY.multiply(BigDecimal.valueOf(pass - i)).divide(BigDecimal.valueOf(pass + 1), 1, RoundingMode.HALF_UP));
            } else {
                percent = FIFTY.multiply(BigDecimal.valueOf(worst - i)).divide(BigDecimal.valueOf(worst - pass), 1, RoundingMode.HALF_UP);
            }
            key.add(new PercentStepData(BigDecimal.valueOf(grades.get(i)), percent));
        }
        return key;
    }

    private static boolean isBetter(BigDecimal a, BigDecimal b, GradeScale scale) {
        int comparison = a.compareTo(b);
        return scale.lowerIsBetter ? comparison < 0 : comparison > 0;
    }
}
