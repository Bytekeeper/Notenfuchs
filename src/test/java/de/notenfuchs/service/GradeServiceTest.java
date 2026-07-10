package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain JUnit 5 unit tests for {@link GradeService}. Deliberately does NOT use
 * {@code @QuarkusTest} and requires no database - GradeService operates purely on
 * in-memory DTOs.
 */
class GradeServiceTest {

    private final GradeService service = new GradeService();

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

    @Test
    void weightedCategoryMean_withFactor() {
        // Grade 1: value 2.0, factor 1.0
        // Grade 2: value 4.0, factor 2.0 (counts twice as much)
        // Expected: (2.0*1.0 + 4.0*2.0) / (1.0+2.0) = (2 + 8) / 3 = 10/3 = 3.3333...
        List<GradeData> grades = List.of(
                new GradeData(new BigDecimal("2.0"), new BigDecimal("1.0")),
                new GradeData(new BigDecimal("4.0"), new BigDecimal("2.0"))
        );

        BigDecimal average = service.calculateCategoryAverage(grades);

        BigDecimal expected = new BigDecimal("10").divide(new BigDecimal("3"), java.math.MathContext.DECIMAL64);
        assertEquals(0, expected.compareTo(average),
                "expected " + expected + " but was " + average);
    }

    @Test
    void subjectAverage_combinesTwoPopulatedCategories_50_50() {
        // Schriftlich (50%): single grade 2.0
        // Muendlich (50%): single grade 4.0
        // Expected subject average: (2.0*50 + 4.0*50) / 100 = 3.0
        CategoryData schriftlich = new CategoryData(new BigDecimal("50"),
                List.of(new GradeData(new BigDecimal("2.0"))));
        CategoryData muendlich = new CategoryData(new BigDecimal("50"),
                List.of(new GradeData(new BigDecimal("4.0"))));

        SubjectAverageResult result = service.calculateSubjectAverage(
                List.of(schriftlich, muendlich), deScale(), RoundingMode.COMMERCIAL);

        assertEquals(new BigDecimal("3.00"), result.rawAverage());
        assertEquals(Integer.valueOf(3), result.finalGrade());
    }

    @Test
    void subjectAverage_emptyCategoryIsExcluded_fromNormalization() {
        // Schriftlich (50%): grades average to 2.0
        // Muendlich (50%): NO grades yet for this student
        // Expected: normalization ignores muendlich entirely -> subject average is just 2.0,
        // NOT (2.0*50 + 0) / 100 = 1.0
        CategoryData schriftlich = new CategoryData(new BigDecimal("50"),
                List.of(new GradeData(new BigDecimal("2.0"))));
        CategoryData muendlichEmpty = new CategoryData(new BigDecimal("50"), List.of());

        SubjectAverageResult result = service.calculateSubjectAverage(
                List.of(schriftlich, muendlichEmpty), deScale(), RoundingMode.COMMERCIAL);

        assertEquals(new BigDecimal("2.00"), result.rawAverage());
        assertEquals(Integer.valueOf(2), result.finalGrade());
    }

    @Test
    void subjectAverage_allCategoriesEmpty_returnsEmptyResult() {
        CategoryData empty1 = new CategoryData(new BigDecimal("50"), List.of());
        CategoryData empty2 = new CategoryData(new BigDecimal("50"), List.of());

        SubjectAverageResult result = service.calculateSubjectAverage(
                List.of(empty1, empty2), deScale(), RoundingMode.COMMERCIAL);

        assertNull(result.rawAverage());
        assertNull(result.finalGrade());
    }

    @Test
    void commercialRounding_atExactHalfBoundary_roundsToHigherNumber() {
        // 2.50 with COMMERCIAL rounding -> 3 (standard half-up, purely numeric,
        // ignores lowerIsBetter even though 3 is numerically "worse" on the DE scale)
        int rounded = service.round(new BigDecimal("2.50"), RoundingMode.COMMERCIAL, true);
        assertEquals(3, rounded);
    }

    @Test
    void inFavorOfStudent_atExactHalfBoundary_lowerIsBetter_roundsToLowerBetterNumber() {
        // 2.50 with IN_FAVOR_OF_STUDENT on a lowerIsBetter=true scale (DE 1-6) -> 2
        // (rounds toward the numerically lower = better grade)
        int rounded = service.round(new BigDecimal("2.50"), RoundingMode.IN_FAVOR_OF_STUDENT, true);
        assertEquals(2, rounded);
    }

    @Test
    void commercialVsInFavorOfStudent_sameInput_differentResult_lowerIsBetterScale() {
        BigDecimal input = new BigDecimal("2.50");

        int commercial = service.round(input, RoundingMode.COMMERCIAL, true);
        int favorStudent = service.round(input, RoundingMode.IN_FAVOR_OF_STUDENT, true);

        assertEquals(3, commercial, "commercial rounding should round up to the numerically higher (worse) grade");
        assertEquals(2, favorStudent, "in-favor-of-student rounding should round down to the numerically lower (better) grade");
    }

    @Test
    void inFavorOfStudent_atExactHalfBoundary_higherIsBetterScale_roundsToHigherBetterNumber() {
        // For a hypothetical scale where higher is better (e.g. 0-15 Punkte),
        // 2.50 with IN_FAVOR_OF_STUDENT should round UP to 3 (toward the better/higher number).
        int rounded = service.round(new BigDecimal("2.50"), RoundingMode.IN_FAVOR_OF_STUDENT, false);
        assertEquals(3, rounded);
    }

    @Test
    void inFavorOfStudent_nonHalfValue_roundsNormally() {
        // Non-half values round the same way in both modes.
        assertEquals(2, service.round(new BigDecimal("2.30"), RoundingMode.IN_FAVOR_OF_STUDENT, true));
        assertEquals(3, service.round(new BigDecimal("2.70"), RoundingMode.IN_FAVOR_OF_STUDENT, true));
    }

    @Test
    void subjectAverage_usesHigherPrecisionInternally_thanDisplayedRawAverage() {
        // Three categories with weights that don't divide evenly, to exercise
        // higher-precision intermediate computation before final 2dp display rounding.
        CategoryData c1 = new CategoryData(new BigDecimal("30"),
                List.of(new GradeData(new BigDecimal("1.0"))));
        CategoryData c2 = new CategoryData(new BigDecimal("30"),
                List.of(new GradeData(new BigDecimal("2.0"))));
        CategoryData c3 = new CategoryData(new BigDecimal("40"),
                List.of(new GradeData(new BigDecimal("3.0"))));

        SubjectAverageResult result = service.calculateSubjectAverage(
                List.of(c1, c2, c3), deScale(), RoundingMode.COMMERCIAL);

        // (1*30 + 2*30 + 3*40) / 100 = (30+60+120)/100 = 210/100 = 2.10
        assertEquals(new BigDecimal("2.10"), result.rawAverage());
        assertEquals(Integer.valueOf(2), result.finalGrade());
    }

    @Test
    void assessmentAverage_plainMeanAcrossStudents_ignoresFactor() {
        // Three students' grades on the SAME assessment ("Leistung") - a class average on
        // one test, not weighted by the assessment's factor (factor is meaningless here,
        // since it's the same assessment for everyone).
        List<BigDecimal> values = List.of(
                new BigDecimal("2.0"), new BigDecimal("3.0"), new BigDecimal("4.0"));

        SubjectAverageResult result = service.calculateAssessmentAverage(values, deScale(), RoundingMode.COMMERCIAL);

        assertEquals(new BigDecimal("3.00"), result.rawAverage());
        assertEquals(Integer.valueOf(3), result.finalGrade());
    }

    @Test
    void assessmentAverage_noGradesYet_returnsEmptyResult() {
        SubjectAverageResult result = service.calculateAssessmentAverage(List.of(), deScale(), RoundingMode.COMMERCIAL);

        assertNull(result.rawAverage());
        assertNull(result.finalGrade());
    }

    @Test
    void assessmentAverage_atHalfBoundary_respectsRoundingMode() {
        List<BigDecimal> values = List.of(new BigDecimal("2.0"), new BigDecimal("3.0"));

        SubjectAverageResult commercial = service.calculateAssessmentAverage(values, deScale(), RoundingMode.COMMERCIAL);
        SubjectAverageResult favorStudent = service.calculateAssessmentAverage(values, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);

        assertEquals(new BigDecimal("2.50"), commercial.rawAverage());
        assertEquals(Integer.valueOf(3), commercial.finalGrade());
        assertEquals(Integer.valueOf(2), favorStudent.finalGrade());
    }
}
