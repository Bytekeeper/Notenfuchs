package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 unit tests for {@link PointsConversionService}. Deliberately does NOT use
 * {@code @QuarkusTest} and requires no database - like {@link GradeServiceTest}, this service
 * operates purely on in-memory DTOs.
 */
class PointsConversionServiceTest {

    private final PointsConversionService service = new PointsConversionService();

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

    /** The two default anchors: 60 points -> grade 1 (best), 20 points -> grade 6 (worst). */
    private static List<PointsGradeBandData> twoAnchorKey() {
        return List.of(
                new PointsGradeBandData(new BigDecimal("60"), new BigDecimal("1")),
                new PointsGradeBandData(new BigDecimal("20"), new BigDecimal("6")));
    }

    @Test
    void pointsAtLowestAnchor_returnsThatAnchorsGradeExactly() {
        BigDecimal grade = service.convert(new BigDecimal("20"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("6").compareTo(grade), "expected grade 6 but was " + grade);
    }

    @Test
    void pointsAtHighestAnchor_returnsThatAnchorsGradeExactly() {
        BigDecimal grade = service.convert(new BigDecimal("60"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("1").compareTo(grade), "expected grade 1 but was " + grade);
    }

    @Test
    void pointsMidwayBetweenTwoAnchors_interpolatesLinearly() {
        // Midpoint of [20, 60] -> midpoint of [6, 1] = 3.5.
        BigDecimal grade = service.convert(new BigDecimal("40"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("3.5").compareTo(grade), "expected grade 3.5 but was " + grade);
    }

    @Test
    void pointsAQuarterBetweenTwoAnchors_interpolatesProportionally() {
        // 30 is 25% of the way from 20 to 60 -> 25% of the way from 6 to 1 = 6 - 0.25*5 = 4.75,
        // rounded to one decimal in favor of the student (lower is better) -> 4.7, not 4.8.
        BigDecimal grade = service.convert(new BigDecimal("30"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("4.7").compareTo(grade), "expected grade 4.7 but was " + grade);
    }

    @Test
    void interpolatedGrade_roundsToOneDecimal_commercial_ignoresFavorDirection() {
        // Same 30-points case as the IN_FAVOR_OF_STUDENT test above (4.75), but COMMERCIAL
        // rounds with standard half-up regardless of which direction favors the student ->
        // 4.75 rounds UP to 4.8, not down to 4.7.
        BigDecimal grade = service.convert(new BigDecimal("30"), twoAnchorKey(), deScale(), RoundingMode.COMMERCIAL);
        assertEquals(0, new BigDecimal("4.8").compareTo(grade), "expected grade 4.8 but was " + grade);
    }

    @Test
    void interpolatedGrade_roundsToOneDecimal_commercial_higherIsBetterScale() {
        // 7/30 -> 0 + (7/30)*10 = 2.3333... . IN_FAVOR_OF_STUDENT on a higher-is-better scale
        // (CEILING) would round ANY excess up to 2.4, but COMMERCIAL's standard half-up only
        // rounds up at .05 or more -> stays at 2.3, proving it truly ignores the favor direction
        // rather than coincidentally agreeing with it.
        List<PointsGradeBandData> bands = List.of(
                new PointsGradeBandData(new BigDecimal("0"), new BigDecimal("0")),
                new PointsGradeBandData(new BigDecimal("30"), new BigDecimal("10")));
        BigDecimal grade = service.convert(new BigDecimal("7"), bands, pointsScaleHigherIsBetter(), RoundingMode.COMMERCIAL);
        assertEquals(0, new BigDecimal("2.3").compareTo(grade), "expected grade 2.3 but was " + grade);
    }

    @Test
    void interpolatedGrade_roundsToOneDecimal_inFavorOfStudent_higherIsBetterScale() {
        // On a higher-is-better scale, "in favor of the student" means rounding UP (toward the
        // higher/better number), the opposite direction from a lowerIsBetter scale.
        List<PointsGradeBandData> bands = List.of(
                new PointsGradeBandData(new BigDecimal("0"), new BigDecimal("0")),
                new PointsGradeBandData(new BigDecimal("20"), new BigDecimal("15")));
        // 5/20 = 25% -> 0 + 0.25*15 = 3.75 -> rounds up to 3.8.
        BigDecimal grade = service.convert(new BigDecimal("5"), bands, pointsScaleHigherIsBetter(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("3.8").compareTo(grade), "expected grade 3.8 but was " + grade);
    }

    @Test
    void pointsBelowLowestAnchor_fallsBackToWorstGradeOnScale_lowerIsBetter() {
        BigDecimal grade = service.convert(new BigDecimal("5"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("6").compareTo(grade));
    }

    @Test
    void pointsBelowLowestAnchor_fallsBackToWorstGradeOnScale_higherIsBetter() {
        List<PointsGradeBandData> bands = List.of(
                new PointsGradeBandData(new BigDecimal("10"), new BigDecimal("15")),
                new PointsGradeBandData(new BigDecimal("50"), new BigDecimal("5")));
        // On a higher-is-better scale the worst grade is the numerically lowest value (min).
        BigDecimal grade = service.convert(new BigDecimal("2"), bands, pointsScaleHigherIsBetter(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("0").compareTo(grade));
    }

    @Test
    void pointsAboveHighestAnchor_clampsToTopAnchorsGrade_noExtrapolation() {
        // 105 is well past the top (60) anchor - clamps to grade 1, doesn't extrapolate below it.
        BigDecimal grade = service.convert(new BigDecimal("105"), twoAnchorKey(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("1").compareTo(grade));
    }

    @Test
    void noBandsAtAll_fallsBackToWorstGradeOnScale() {
        BigDecimal grade = service.convert(new BigDecimal("40"), List.of(), deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("6").compareTo(grade));
    }

    @Test
    void scaleAgnostic_higherIsBetterScale_interpolatesAscending() {
        // On a "higher is better" scale a sensible key ascends: higher points -> higher grade -
        // proving interpolation doesn't hardcode "more points means a lower/better grade
        // number" (true only for a lowerIsBetter scale like DE 1-6).
        List<PointsGradeBandData> bands = List.of(
                new PointsGradeBandData(new BigDecimal("0"), new BigDecimal("0")),
                new PointsGradeBandData(new BigDecimal("20"), new BigDecimal("15")));

        BigDecimal grade = service.convert(new BigDecimal("10"), bands, pointsScaleHigherIsBetter(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("7.5").compareTo(grade), "midpoint of 0..20 points should give the midpoint grade 7.5");
    }

    @Test
    void threeBands_interpolatesWithinTheNearestSegment() {
        // A teacher-added middle anchor bends the line: 40 points should use the (20,6)-(40,3)
        // segment, not the full (20,6)-(60,1) span.
        List<PointsGradeBandData> bands = List.of(
                new PointsGradeBandData(new BigDecimal("20"), new BigDecimal("6")),
                new PointsGradeBandData(new BigDecimal("40"), new BigDecimal("3")),
                new PointsGradeBandData(new BigDecimal("60"), new BigDecimal("1")));

        assertEquals(0, new BigDecimal("3").compareTo(service.convert(new BigDecimal("40"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
        // Midpoint of the (20,6)-(40,3) segment: 30 points -> grade 4.5.
        assertEquals(0, new BigDecimal("4.5").compareTo(service.convert(new BigDecimal("30"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
        // Midpoint of the (40,3)-(60,1) segment: 50 points -> grade 2.
        assertEquals(0, new BigDecimal("2").compareTo(service.convert(new BigDecimal("50"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
    }

    @Test
    void bandsOutOfOrderInList_stillInterpolatesCorrectly() {
        // Order-independence: bands deliberately not sorted.
        List<PointsGradeBandData> shuffled = List.of(
                new PointsGradeBandData(new BigDecimal("60"), new BigDecimal("1")),
                new PointsGradeBandData(new BigDecimal("20"), new BigDecimal("6")),
                new PointsGradeBandData(new BigDecimal("40"), new BigDecimal("3")));

        assertEquals(0, new BigDecimal("4.5").compareTo(service.convert(new BigDecimal("30"), shuffled, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
    }

    @Test
    void defaultBands_twoAnchorBandsAt60And20() {
        List<PointsGradeBandData> bands = service.defaultBands(deScale());

        assertEquals(2, bands.size());
        assertEquals(0, new BigDecimal("60").compareTo(bands.get(0).minPoints()));
        assertEquals(0, new BigDecimal("1.00").compareTo(bands.get(0).gradeValue()));
        assertEquals(0, new BigDecimal("20").compareTo(bands.get(1).minPoints()));
        assertEquals(0, new BigDecimal("6.00").compareTo(bands.get(1).gradeValue()));
    }

    @Test
    void defaultBands_scaleAgnostic_higherIsBetterRange() {
        List<PointsGradeBandData> bands = service.defaultBands(pointsScaleHigherIsBetter());

        assertEquals(0, new BigDecimal("15.00").compareTo(bands.get(0).gradeValue()), "the 60-point band should map to the best (max) grade");
        assertEquals(0, new BigDecimal("0.00").compareTo(bands.get(1).gradeValue()), "the 20-point band should map to the worst (min) grade");
    }

    @Test
    void defaultBandsThenConvert_interpolatesAcrossTheFullRange() {
        List<PointsGradeBandData> bands = service.defaultBands(deScale());

        assertEquals(0, new BigDecimal("1").compareTo(service.convert(new BigDecimal("60"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
        assertEquals(0, new BigDecimal("3.5").compareTo(service.convert(new BigDecimal("40"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
        assertEquals(0, new BigDecimal("6").compareTo(service.convert(new BigDecimal("20"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
        // Below every band -> falls back to the scale's worst grade.
        assertEquals(0, new BigDecimal("6").compareTo(service.convert(new BigDecimal("5"), bands, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT)));
    }
}
