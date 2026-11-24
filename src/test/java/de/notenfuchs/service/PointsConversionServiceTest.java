package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsConversionServiceTest {

    private final PointsConversionService service = new PointsConversionService();

    private static GradeScale deScale() {
        GradeScale scale = new GradeScale();
        scale.min = new BigDecimal("1");
        scale.max = new BigDecimal("6");
        scale.lowerIsBetter = true;
        scale.atRiskFrom = new BigDecimal("5");
        return scale;
    }

    private static GradeScale fifteenPointScale() {
        GradeScale scale = new GradeScale();
        scale.min = new BigDecimal("0");
        scale.max = new BigDecimal("15");
        scale.lowerIsBetter = false;
        return scale;
    }

    private static PointsGradeBandData step(String points, String grade) {
        return new PointsGradeBandData(new BigDecimal(points), new BigDecimal(grade));
    }

    private static void assertGrade(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static final List<PointsGradeBandData> KEY_40 = List.of(
            step("35", "1"), step("30", "2"), step("25", "3"), step("20", "4"), step("10", "5"));

    @Test
    void theStepIsReachedAtItsPoints() {
        assertGrade("2", service.convert(new BigDecimal("30"), KEY_40, deScale(), false));
        assertGrade("3", service.convert(new BigDecimal("29.5"), KEY_40, deScale(), false));
    }

    @Test
    void belowEveryStepIsTheWorstGradeAndBonusPointsTheBest() {
        assertGrade("6", service.convert(new BigDecimal("9.5"), KEY_40, deScale(), false));
        assertGrade("1", service.convert(new BigDecimal("44"), KEY_40, deScale(), false));
    }

    @Test
    void aTendencyRowIsAStepLikeAnyOther() {
        List<PointsGradeBandData> withTendency = List.of(step("35", "1"), step("32", "1.7"), step("30", "2"));
        assertGrade("1.7", service.convert(new BigDecimal("33"), withTendency, deScale(), false));
    }

    @Test
    void worksWhereHigherIsBetter() {
        List<PointsGradeBandData> key = List.of(step("90", "15"), step("50", "5"));
        assertGrade("5", service.convert(new BigDecimal("60"), key, fifteenPointScale(), false));
        assertGrade("0", service.convert(new BigDecimal("49"), key, fifteenPointScale(), false));
    }

    /** A real teacher's key for 40 points: 100 % → 1,0, 50 % → 3,5, 20 % → 6. */
    private static final List<PointsGradeBandData> SLIDING_40 = List.of(step("40", "1"), step("20", "3.5"), step("8", "6"));

    @Test
    void slidingMovesEvenlyBetweenRowsAndRoundsInTheStudentsFavor() {
        assertGrade("3.5", service.convert(new BigDecimal("20"), SLIDING_40, deScale(), true));
        // 30 points: 2,25 → 2,2. 14 points: halfway between 8 and 20 → 4,75 → 4,7.
        assertGrade("2.2", service.convert(new BigDecimal("30"), SLIDING_40, deScale(), true));
        assertGrade("4.7", service.convert(new BigDecimal("14"), SLIDING_40, deScale(), true));
    }

    @Test
    void slidingStopsAtTheEndsOfTheScale() {
        assertGrade("1", service.convert(new BigDecimal("44"), SLIDING_40, deScale(), true));
        assertGrade("6", service.convert(new BigDecimal("2"), SLIDING_40, deScale(), true));
    }

    @Test
    void slidingWhereHigherIsBetterRoundsUp() {
        List<PointsGradeBandData> key = List.of(step("30", "15"), step("0", "0"));
        // 2,5 of 30 points: 1,25 → 1,3, the better side.
        assertGrade("1.3", service.convert(new BigDecimal("2.5"), key, fifteenPointScale(), true));
    }

    @Test
    void aBetterGradeMustNeedMorePoints() {
        assertTrue(service.isOrdered(KEY_40, deScale()));
        assertFalse(service.isOrdered(List.of(step("30", "1"), step("35", "2")), deScale()));
        assertFalse(service.isOrdered(List.of(step("30", "1"), step("30", "2")), deScale()));
    }

    @Test
    void percentagesBecomeHalfPointsRoundedDown() {
        // 87.5 % of 37 = 32.375 → 32; 62.5 % of 37 = 23.125 → 23; 50 % of 37 = 18.5.
        List<PointsGradeBandData> steps = service.stepsFromKey(new BigDecimal("37"), List.of(
                new PercentStepData(new BigDecimal("1"), new BigDecimal("87.5")),
                new PercentStepData(new BigDecimal("3"), new BigDecimal("62.5")),
                new PercentStepData(new BigDecimal("4"), new BigDecimal("50"))));
        assertGrade("32", steps.get(0).points());
        assertGrade("23", steps.get(1).points());
        assertGrade("18.5", steps.get(2).points());
    }

    @Test
    void defaultKeyPutsThePassGradeAtHalf() {
        List<PercentStepData> key = service.defaultKey(deScale());
        assertEquals(5, key.size());
        List<String> expected = List.of("87.5", "75", "62.5", "50", "25");
        for (int i = 0; i < 5; i++) {
            assertGrade(String.valueOf(i + 1), key.get(i).gradeValue());
            assertGrade(expected.get(i), key.get(i).minPercent());
        }
    }

    @Test
    void defaultKeyWithoutAtRiskSpreadsEvenly() {
        List<PercentStepData> key = service.defaultKey(fifteenPointScale());
        assertEquals(15, key.size());
        assertGrade("15", key.get(0).gradeValue());
        assertGrade("93.8", key.get(0).minPercent());
        assertGrade("1", key.get(14).gradeValue());
        assertGrade("6.3", key.get(14).minPercent());
    }
}
