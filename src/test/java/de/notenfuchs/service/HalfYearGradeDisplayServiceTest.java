package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.HalfYearGradeDisplay;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain JUnit 5 unit tests for {@link HalfYearGradeDisplayService}, like {@link GradeServiceTest}:
 * no {@code @QuarkusTest}, no database.
 */
class HalfYearGradeDisplayServiceTest {

    private final HalfYearGradeDisplayService service = new HalfYearGradeDisplayService();

    private static GradeScale deScale() {
        GradeScale scale = new GradeScale();
        scale.name = "DE 1-6";
        scale.min = new BigDecimal("1");
        scale.max = new BigDecimal("6");
        scale.lowerIsBetter = true;
        return scale;
    }

    private static GradeScale pointsScaleHigherIsBetter() {
        GradeScale scale = new GradeScale();
        scale.name = "Punkte 0-15";
        scale.min = new BigDecimal("0");
        scale.max = new BigDecimal("15");
        scale.lowerIsBetter = false;
        return scale;
    }

    // ---- roundToHalf ----

    @Test
    void roundToHalf_belowQuarterBoundary_roundsDownToWholeStep() {
        assertEquals(new BigDecimal("2.0"), service.roundToHalf(new BigDecimal("2.24")));
    }

    @Test
    void roundToHalf_atQuarterBoundary_roundsUpToHalfStep() {
        // Ties round up ("kaufmaennisch" style, consistent with the rest of this codebase).
        assertEquals(new BigDecimal("2.5"), service.roundToHalf(new BigDecimal("2.25")));
    }

    @Test
    void roundToHalf_justBelowNextHalfStep_roundsDownToHalfStep() {
        assertEquals(new BigDecimal("2.5"), service.roundToHalf(new BigDecimal("2.74")));
    }

    @Test
    void roundToHalf_atThreeQuarterBoundary_roundsUpToNextWholeStep() {
        assertEquals(new BigDecimal("3.0"), service.roundToHalf(new BigDecimal("2.75")));
    }

    @Test
    void roundToHalf_alreadyOnAHalfStep_staysUnchanged() {
        assertEquals(new BigDecimal("3.0"), service.roundToHalf(new BigDecimal("3.00")));
        assertEquals(new BigDecimal("2.5"), service.roundToHalf(new BigDecimal("2.50")));
    }

    // ---- tendencySuffix ----

    @Test
    void tendencySuffix_exactMatch_isAlwaysPlain_evenAtZeroThreshold() {
        assertEquals("", service.tendencySuffix(new BigDecimal("3.0"), 3, 0, deScale()));
    }

    @Test
    void tendencySuffix_withinThreshold_isPlain() {
        // 3.1 is exactly 10% of a whole step above 3 - inclusive boundary.
        assertEquals("", service.tendencySuffix(new BigDecimal("3.1"), 3, 10, deScale()));
        assertEquals("", service.tendencySuffix(new BigDecimal("2.9"), 3, 10, deScale()));
    }

    @Test
    void tendencySuffix_lowerIsBetter_leaningTowardLowerNumber_isPlus() {
        // 2.8 is below the rounded grade 3, on the DE 1-6 scale that means closer to the better
        // (numerically lower) neighbor 2.
        assertEquals("+", service.tendencySuffix(new BigDecimal("2.8"), 3, 10, deScale()));
    }

    @Test
    void tendencySuffix_lowerIsBetter_leaningTowardHigherNumber_isMinus() {
        // 3.2 is above the rounded grade 3, toward the worse neighbor 4.
        assertEquals("-", service.tendencySuffix(new BigDecimal("3.2"), 3, 10, deScale()));
    }

    @Test
    void tendencySuffix_higherIsBetter_directionIsFlipped() {
        // On a Punkte scale (higher = better), leaning toward the higher neighbor is "+".
        assertEquals("+", service.tendencySuffix(new BigDecimal("9.2"), 9, 10, pointsScaleHigherIsBetter()));
        assertEquals("-", service.tendencySuffix(new BigDecimal("8.8"), 9, 10, pointsScaleHigherIsBetter()));
    }

    @Test
    void tendencySuffix_zeroThreshold_anyDeviationGetsASuffix() {
        assertEquals("-", service.tendencySuffix(new BigDecimal("3.01"), 3, 0, deScale()));
        assertEquals("+", service.tendencySuffix(new BigDecimal("2.99"), 3, 0, deScale()));
    }

    // ---- label ----

    @Test
    void label_noGradesYet_isNull() {
        assertNull(service.label(null, null, HalfYearGradeDisplay.WHOLE, 10, deScale()));
    }

    @Test
    void label_whole_noTendencyConfigured_isJustTheFinalGrade() {
        assertEquals("3", service.label(new BigDecimal("3.2"), 3, HalfYearGradeDisplay.WHOLE, null, deScale()));
    }

    @Test
    void label_whole_withTendency_appendsSuffix() {
        assertEquals("3-", service.label(new BigDecimal("3.2"), 3, HalfYearGradeDisplay.WHOLE, 10, deScale()));
        assertEquals("3+", service.label(new BigDecimal("2.8"), 3, HalfYearGradeDisplay.WHOLE, 10, deScale()));
        assertEquals("3", service.label(new BigDecimal("3.0"), 3, HalfYearGradeDisplay.WHOLE, 10, deScale()));
    }

    /**
     * The combination this service exists to make impossible: HALF granularity ignores any
     * configured tendency threshold entirely, never producing something like "3.5+" for a raw
     * average of 3.4 (which would be nonsensical - 3.4 is numerically *better* than 3.5 on a
     * lower-is-better scale, so a "+" suggesting it's leaning toward an even better neighbor
     * would contradict the number it's attached to).
     */
    @Test
    void label_half_ignoresTendencyThreshold_evenIfConfigured() {
        assertEquals("3.5", service.label(new BigDecimal("3.4"), 3, HalfYearGradeDisplay.HALF, 10, deScale()));
    }

    @Test
    void label_half_roundsToNearestHalfGrade() {
        assertEquals("2.5", service.label(new BigDecimal("2.6"), 3, HalfYearGradeDisplay.HALF, null, deScale()));
    }
}
