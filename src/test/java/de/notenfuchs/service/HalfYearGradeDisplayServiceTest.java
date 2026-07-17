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

    @Test
    void label_half_noTendencyConfigured_roundsToNearestHalfGrade() {
        assertEquals("2.5", service.label(new BigDecimal("2.6"), 3, HalfYearGradeDisplay.HALF, null, deScale()));
    }

    /**
     * The worked example this feature is built from: with a 10% threshold, HALF reuses WHOLE's
     * tendency computation (anchored on finalGrade 2) and refines a would-be suffix into the
     * neighboring half-grade 2.5 once the raw average is close enough to it. 2.1 stays plain
     * (inside grade 2's own zone); 2.2/2.3 fall in the "murky middle" between grade 2 and 2.5 and
     * get a suffix exactly like WHOLE would ("2-"); 2.4/2.5 are close enough to 2.5 to show it
     * bare instead.
     */
    @Test
    void label_half_withTendency_refinesSuffixIntoNeighboringHalfGrade() {
        assertEquals("2", service.label(new BigDecimal("2.1"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("2-", service.label(new BigDecimal("2.2"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("2-", service.label(new BigDecimal("2.3"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("2.5", service.label(new BigDecimal("2.4"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("2.5", service.label(new BigDecimal("2.5"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
    }

    /**
     * The symmetric case on the "leaning better" side: 3.6 is outside whole grade 4's plain zone
     * (leaning "+") but close enough to the half-grade 3.5 to show that instead - mirroring the
     * 2.4 case above from the other direction.
     */
    @Test
    void label_half_withTendency_refinesSuffixIntoNeighboringHalfGrade_fromAbove() {
        assertEquals("3.5", service.label(new BigDecimal("3.6"), 4, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("4+", service.label(new BigDecimal("3.8"), 4, HalfYearGradeDisplay.HALF, 10, deScale()));
    }

    /**
     * 2.5 is an exact tie, which a subject's own RoundingMode can break either way
     * (IN_FAVOR_OF_STUDENT floors to 2, COMMERCIAL rounds up to 3 - see GradeServiceTest). Both
     * must converge on the same half-grade here, since the refinement always checks the neighbor
     * in whichever direction the tie happened to be broken.
     */
    @Test
    void label_half_withTendency_exactTieConvergesRegardlessOfFinalGradeTieBreak() {
        assertEquals("2.5", service.label(new BigDecimal("2.5"), 2, HalfYearGradeDisplay.HALF, 10, deScale()));
        assertEquals("2.5", service.label(new BigDecimal("2.5"), 3, HalfYearGradeDisplay.HALF, 10, deScale()));
    }

    @Test
    void label_half_withTendency_higherIsBetter_directionIsFlipped() {
        assertEquals("9.5", service.label(new BigDecimal("9.45"), 9, HalfYearGradeDisplay.HALF, 10, pointsScaleHigherIsBetter()));
    }
}
