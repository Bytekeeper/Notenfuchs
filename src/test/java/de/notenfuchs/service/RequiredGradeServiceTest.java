package de.notenfuchs.service;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.service.RequiredGradeService.Requirement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredGradeServiceTest {

    private final RequiredGradeService service = new RequiredGradeService();

    @Test
    void oneCategory_requiredGradeFollowsTheRoundingMode() {
        // Current: one 3. Next Leistung with the same factor: average = (3 + x) / 2.
        List<CategoryData> current = List.of(category("100", grade("3")));

        // COMMERCIAL: 2.50 rounds to 3, so a 2 needs an average below 2.5, i.e. x = 1.9.
        List<Requirement> commercial = service.requiredGrades(current, 0, BigDecimal.ONE, deScale(), RoundingMode.COMMERCIAL);
        assertEquals(new BigDecimal("1.9"), target(commercial, 2).requiredGrade());
        // IN_FAVOR_OF_STUDENT: 2.50 rounds to 2, so x = 2.0 is enough.
        List<Requirement> inFavor = service.requiredGrades(current, 0, BigDecimal.ONE, deScale(), RoundingMode.IN_FAVOR_OF_STUDENT);
        assertEquals(0, new BigDecimal("2.0").compareTo(target(inFavor, 2).requiredGrade()));

        assertTrue(target(commercial, 1).unreachable());
        assertEquals(new BigDecimal("5.9"), target(commercial, 4).requiredGrade());
        assertTrue(target(commercial, 5).alreadySafe());
        assertEquals(6, commercial.size());
    }

    @Test
    void emptyTargetCategory_startsCountingWithTheNewLeistung() {
        // Mündlich has no grade yet, so it doesn't count today; the next Leistung brings it in at 50 %.
        List<CategoryData> current = List.of(category("50", grade("2")), category("50"));

        List<Requirement> result = service.requiredGrades(current, 1, BigDecimal.ONE, deScale(), RoundingMode.COMMERCIAL);

        assertEquals(new BigDecimal("2.9"), target(result, 2).requiredGrade());
    }

    @Test
    void higherIsBetterScale_searchesUpward() {
        GradeScale points = new GradeScale();
        points.min = new BigDecimal("0");
        points.max = new BigDecimal("15");
        points.lowerIsBetter = false;
        List<CategoryData> current = List.of(category("100", grade("10")));

        List<Requirement> result = service.requiredGrades(current, 0, BigDecimal.ONE, points, RoundingMode.COMMERCIAL);

        assertEquals(15, result.get(0).targetGrade());
        // 12 needs an average of at least 11.5: (10 + x) / 2 >= 11.5, so x = 13.
        assertEquals(0, new BigDecimal("13").compareTo(target(result, 12).requiredGrade()));
    }

    private static Requirement target(List<Requirement> requirements, int targetGrade) {
        return requirements.stream().filter(r -> r.targetGrade() == targetGrade).findFirst().orElseThrow();
    }

    private static CategoryData category(String weight, GradeData... grades) {
        return new CategoryData(new BigDecimal(weight), List.of(grades));
    }

    private static GradeData grade(String value) {
        return new GradeData(new BigDecimal(value), BigDecimal.ONE);
    }

    private static GradeScale deScale() {
        GradeScale scale = new GradeScale();
        scale.min = new BigDecimal("1");
        scale.max = new BigDecimal("6");
        scale.lowerIsBetter = true;
        return scale;
    }
}
