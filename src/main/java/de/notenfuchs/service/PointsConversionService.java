package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, DB-free conversion of achieved raw points into a grade value on the subject's
 * {@link GradeScale}, via a configurable Notenschlüssel (a list of points-to-grade anchor
 * points, {@link PointsGradeBandData}). Like {@link GradeService}, this operates only on
 * plain in-memory data so it's unit-testable without a database.
 *
 * <p>The result is never frozen: callers are expected to re-run {@link #convert} on every
 * read (grid render, average calculation, ...) rather than persisting it, so editing the raw
 * points or the bands always recomputes the grade live - consistent with this app's
 * anti-freeze design principle (see ROADMAP.md).
 */
public class PointsConversionService {

    private static final MathContext CALC_CONTEXT = MathContext.DECIMAL64;
    private static final int DISPLAY_SCALE = 1;

    /** Default points thresholds offered when marking an Assessment points-based (see {@link #defaultBands}). */
    private static final int[] DEFAULT_THRESHOLDS = {60, 20};

    /**
     * Converts achieved points into a grade value by linearly interpolating between the two
     * bands whose {@code minPoints} bracket the achieved points - so the grade moves smoothly
     * with points instead of jumping at each threshold. Points at or above the highest band's
     * threshold clamp to that band's grade (no extrapolation for bonus points); points below
     * the lowest band's threshold fall back to the worst grade on the scale (e.g. an
     * incomplete Notenschlüssel with no low-points floor band).
     *
     * <p>The result is rounded to one decimal place per the given {@code roundingMode} - the
     * same per-Assessment choice as {@code Subject#roundingMode} offers for the whole-grade
     * average, but applied here to the derived one-decimal grade instead of a whole number:
     * {@link RoundingMode#IN_FAVOR_OF_STUDENT} always rounds toward the better grade on the
     * scale (consulting {@code lowerIsBetter}), so interpolation never nudges a borderline
     * points value to a worse grade than it earned; {@link RoundingMode#COMMERCIAL} rounds
     * with standard numeric half-up rounding, ignoring which direction that favors.
     *
     * @param points       achieved points
     * @param bands        the Notenschlüssel bands to interpolate between (order-independent,
     *                     needs at least one to produce anything but the scale's worst-grade
     *                     fallback)
     * @param scale        the subject's grade scale, consulted for the fallback and (for
     *                     {@code IN_FAVOR_OF_STUDENT}) the rounding direction
     * @param roundingMode the assessment's rounding policy for the derived grade
     * @return the derived grade value, linearly interpolated between the bracketing bands and
     *         rounded to one decimal per {@code roundingMode}
     */
    public BigDecimal convert(BigDecimal points, List<PointsGradeBandData> bands, GradeScale scale,
                               RoundingMode roundingMode) {
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
        return round(rawConvert(points, bands, scale), scale, roundingMode);
    }

    private BigDecimal rawConvert(BigDecimal points, List<PointsGradeBandData> bands, GradeScale scale) {
        if (bands == null || bands.isEmpty()) {
            return scale.lowerIsBetter ? scale.max : scale.min;
        }

        List<PointsGradeBandData> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(PointsGradeBandData::minPoints));

        PointsGradeBandData lowest = sorted.get(0);
        if (points.compareTo(lowest.minPoints()) < 0) {
            return scale.lowerIsBetter ? scale.max : scale.min;
        }

        PointsGradeBandData highest = sorted.get(sorted.size() - 1);
        if (points.compareTo(highest.minPoints()) >= 0) {
            return highest.gradeValue();
        }

        for (int i = 0; i < sorted.size() - 1; i++) {
            PointsGradeBandData lower = sorted.get(i);
            PointsGradeBandData upper = sorted.get(i + 1);
            if (points.compareTo(upper.minPoints()) < 0) {
                return interpolate(points, lower, upper);
            }
        }
        // Unreachable: points is already known to be in [lowest, highest), and every value in
        // that range falls into exactly one consecutive [lower, upper) segment above.
        return highest.gradeValue();
    }

    /** Linearly interpolates the grade for {@code points} between two adjacent bands. */
    private BigDecimal interpolate(BigDecimal points, PointsGradeBandData lower, PointsGradeBandData upper) {
        BigDecimal pointsSpan = upper.minPoints().subtract(lower.minPoints());
        if (pointsSpan.compareTo(BigDecimal.ZERO) == 0) {
            // Two bands defined at the same points threshold - nothing sensible to interpolate.
            return lower.gradeValue();
        }
        BigDecimal fraction = points.subtract(lower.minPoints()).divide(pointsSpan, CALC_CONTEXT);
        BigDecimal gradeSpan = upper.gradeValue().subtract(lower.gradeValue());
        return lower.gradeValue().add(fraction.multiply(gradeSpan, CALC_CONTEXT), CALC_CONTEXT);
    }

    /**
     * Rounds to one decimal place per {@code roundingMode}: {@code COMMERCIAL} uses standard
     * numeric half-up rounding; {@code IN_FAVOR_OF_STUDENT} always rounds toward the better
     * grade on the scale (never just at the exact half, unlike {@code GradeService}'s whole-
     * number rounding of the same name - here every fractional digit gets truncated in the
     * student's favor, not just an exact .5).
     */
    private BigDecimal round(BigDecimal grade, GradeScale scale, RoundingMode roundingMode) {
        if (roundingMode == RoundingMode.COMMERCIAL) {
            return grade.setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
        }
        java.math.RoundingMode mode = scale.lowerIsBetter ? java.math.RoundingMode.FLOOR : java.math.RoundingMode.CEILING;
        return grade.setScale(DISPLAY_SCALE, mode);
    }

    /**
     * A minimal starting Notenschlüssel for a freshly points-based Assessment: two anchor
     * bands at points thresholds 60 and 20, mapped to the best and worst grade on the
     * subject's actual scale (not hardcoded to 1-6). Since {@link #convert} interpolates
     * linearly between bands, these two anchors alone already produce a smooth grade across
     * the whole scale - there's no known max-points to place them against, so this is
     * deliberately just a starting point the teacher is expected to adjust to their test, add
     * intermediate Stufen to (for a non-linear curve), or otherwise edit freely.
     */
    public List<PointsGradeBandData> defaultBands(GradeScale scale) {
        BigDecimal best = (scale.lowerIsBetter ? scale.min : scale.max).setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
        BigDecimal worst = (scale.lowerIsBetter ? scale.max : scale.min).setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
        return List.of(
                new PointsGradeBandData(new BigDecimal(DEFAULT_THRESHOLDS[0]), best),
                new PointsGradeBandData(new BigDecimal(DEFAULT_THRESHOLDS[1]), worst));
    }
}
