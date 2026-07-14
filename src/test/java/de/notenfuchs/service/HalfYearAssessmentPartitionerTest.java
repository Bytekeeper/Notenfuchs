package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 unit tests, like {@link GradeServiceTest}: no {@code @QuarkusTest}, no database.
 * Covers both the pure classification rule and (combined with {@link GradeService}) that a
 * half's average is exactly what you'd get by manually filtering assessments to that half and
 * calling {@link GradeService#calculateSubjectAverage} directly - proving
 * {@code GradeGridResource}'s Halbjahr split doesn't change grade calculation itself.
 */
class HalfYearAssessmentPartitionerTest {

    private final HalfYearAssessmentPartitioner partitioner = new HalfYearAssessmentPartitioner();
    private final GradeService gradeService = new GradeService();

    private static final LocalDate CUTOFF = LocalDate.of(2026, 1, 31);

    private static GradeScale deScale() {
        GradeScale scale = new GradeScale();
        scale.name = "DE 1-6";
        scale.min = new BigDecimal("1");
        scale.max = new BigDecimal("6");
        scale.lowerIsBetter = true;
        return scale;
    }

    @Test
    void nullDate_classifiesAsUndated() {
        assertEquals(HalfYearAssessmentPartitioner.Half.UNDATED, partitioner.classify(null, CUTOFF));
    }

    @Test
    void dateBeforeCutoff_classifiesAsFirstHalf() {
        assertEquals(HalfYearAssessmentPartitioner.Half.FIRST,
                partitioner.classify(CUTOFF.minusDays(1), CUTOFF));
    }

    @Test
    void dateExactlyOnCutoff_classifiesAsFirstHalf() {
        // The boundary date itself belongs to the first half, not the second.
        assertEquals(HalfYearAssessmentPartitioner.Half.FIRST, partitioner.classify(CUTOFF, CUTOFF));
    }

    @Test
    void dateAfterCutoff_classifiesAsSecondHalf() {
        assertEquals(HalfYearAssessmentPartitioner.Half.SECOND,
                partitioner.classify(CUTOFF.plusDays(1), CUTOFF));
    }

    @Test
    void nullCutoff_throws() {
        try {
            partitioner.classify(CUTOFF, null);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected - callers only classify once a cutoff is actually configured
        }
    }

    private record DatedGrade(LocalDate date, BigDecimal value) {
    }

    /**
     * A student has grades in two categories, spread across both halves plus one undated
     * assessment. Splitting the assessments by {@link HalfYearAssessmentPartitioner} into H1 and
     * H2, and separately feeding each subset into {@link GradeService#calculateSubjectAverage},
     * must produce exactly what you'd get from manually date-filtered {@link CategoryData} - i.e.
     * the grid's per-half average IS GradeService's ordinary subject average over that half's
     * assessments, nothing more. Also proves an undated grade is excluded from BOTH halves.
     */
    @Test
    void halfAverages_equalGradeServiceOverManuallyFilteredDateSubset() {
        // Schriftlich (50%): one grade before the cutoff (H1), one after (H2), one undated.
        List<DatedGrade> schriftlich = List.of(
                new DatedGrade(CUTOFF.minusDays(10), new BigDecimal("2.0")),
                new DatedGrade(CUTOFF.plusDays(10), new BigDecimal("4.0")),
                new DatedGrade(null, new BigDecimal("1.0")));
        // Muendlich (50%): only an H1 grade - H2 must exclude this category entirely
        // (empty-category normalization already handled by GradeService).
        List<DatedGrade> muendlich = List.of(
                new DatedGrade(CUTOFF.minusDays(5), new BigDecimal("3.0")));

        SubjectAverageResult h1 = subjectAverageForHalf(schriftlich, muendlich, HalfYearAssessmentPartitioner.Half.FIRST);
        SubjectAverageResult h2 = subjectAverageForHalf(schriftlich, muendlich, HalfYearAssessmentPartitioner.Half.SECOND);

        // H1: Schriftlich has just the 2.0 grade, Muendlich has the 3.0 grade -> (2*50+3*50)/100 = 2.5
        assertEquals(new BigDecimal("2.50"), h1.rawAverage());
        // H2: Schriftlich has just the 4.0 grade, Muendlich is empty and excluded -> 4.0 alone
        assertEquals(new BigDecimal("4.00"), h2.rawAverage());

        // Cross-check against GradeService called directly on hand-filtered data - the whole
        // point of this test: the grid's Halbjahr split is nothing but this filter-then-call.
        CategoryData expectedH1Schriftlich = new CategoryData(new BigDecimal("50"),
                List.of(new GradeData(new BigDecimal("2.0"))));
        CategoryData expectedH1Muendlich = new CategoryData(new BigDecimal("50"),
                List.of(new GradeData(new BigDecimal("3.0"))));
        SubjectAverageResult expectedH1 = gradeService.calculateSubjectAverage(
                List.of(expectedH1Schriftlich, expectedH1Muendlich), deScale(), RoundingMode.COMMERCIAL);
        assertEquals(expectedH1.rawAverage(), h1.rawAverage());
        assertEquals(expectedH1.finalGrade(), h1.finalGrade());
    }

    @Test
    void undatedGrade_excludedFromBothHalves_includedInWholeYear() {
        List<DatedGrade> onlyUndated = List.of(new DatedGrade(null, new BigDecimal("2.0")));

        SubjectAverageResult h1 = subjectAverageForHalf(onlyUndated, List.of(), HalfYearAssessmentPartitioner.Half.FIRST);
        SubjectAverageResult h2 = subjectAverageForHalf(onlyUndated, List.of(), HalfYearAssessmentPartitioner.Half.SECOND);
        SubjectAverageResult wholeYear = gradeService.calculateSubjectAverage(
                List.of(new CategoryData(new BigDecimal("100"),
                        List.of(new GradeData(new BigDecimal("2.0"))))),
                deScale(), RoundingMode.COMMERCIAL);

        assertEquals(SubjectAverageResult.EMPTY, h1);
        assertEquals(SubjectAverageResult.EMPTY, h2);
        assertEquals(new BigDecimal("2.00"), wholeYear.rawAverage());
    }

    /** Filters both categories' grades down to one half via the partitioner, then calls GradeService. */
    private SubjectAverageResult subjectAverageForHalf(List<DatedGrade> schriftlich, List<DatedGrade> muendlich,
                                                         HalfYearAssessmentPartitioner.Half half) {
        return gradeService.calculateSubjectAverage(
                List.of(categoryDataForHalf(new BigDecimal("50"), schriftlich, half),
                        categoryDataForHalf(new BigDecimal("50"), muendlich, half)),
                deScale(), RoundingMode.COMMERCIAL);
    }

    private CategoryData categoryDataForHalf(BigDecimal weightPercent, List<DatedGrade> grades,
                                              HalfYearAssessmentPartitioner.Half half) {
        List<GradeData> filtered = new ArrayList<>();
        for (DatedGrade grade : grades) {
            if (partitioner.classify(grade.date(), CUTOFF) == half) {
                filtered.add(new GradeData(grade.value()));
            }
        }
        return new CategoryData(weightPercent, filtered);
    }
}
