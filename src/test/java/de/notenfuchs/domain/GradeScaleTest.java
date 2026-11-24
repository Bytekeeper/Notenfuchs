package de.notenfuchs.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradeScaleTest {

    @Test
    void atRisk_followsTheScaleDirection() {
        GradeScale germanScale = scale(true, "5");
        assertTrue(germanScale.isAtRisk(5));
        assertTrue(germanScale.isAtRisk(6));
        assertFalse(germanScale.isAtRisk(4));

        GradeScale pointsScale = scale(false, "4");
        assertTrue(pointsScale.isAtRisk(4));
        assertTrue(pointsScale.isAtRisk(0));
        assertFalse(pointsScale.isAtRisk(5));
    }

    @Test
    void noThresholdOrNoGrade_isNeverAtRisk() {
        assertFalse(scale(true, null).isAtRisk(6));
        assertFalse(scale(true, "5").isAtRisk(null));
    }

    @Test
    void tendency_plusIsAlwaysTheBetterSide() {
        GradeScale german = scale(true, null);
        german.tendencyStep = new BigDecimal("0.3");
        assertEquals(new BigDecimal("2.3"), german.parseGrade("2-"));
        assertEquals(new BigDecimal("1.7"), german.parseGrade(" 2 + "));
        assertEquals(new BigDecimal("2.3"), german.parseGrade("2,3"));

        GradeScale higherIsBetter = scale(false, null);
        higherIsBetter.tendencyStep = new BigDecimal("0.3");
        assertEquals(new BigDecimal("12.3"), higherIsBetter.parseGrade("12+"));
        assertEquals(new BigDecimal("11.7"), higherIsBetter.parseGrade("12-"));
    }

    @Test
    void tendency_isRejectedWithoutStepOrAfterADecimal() {
        GradeScale german = scale(true, null);
        assertThrows(NumberFormatException.class, () -> german.parseGrade("2-"));
        german.tendencyStep = new BigDecimal("0.3");
        assertThrows(NumberFormatException.class, () -> german.parseGrade("2.5-"));
        assertThrows(NumberFormatException.class, () -> german.parseGrade("-"));
    }

    @Test
    void format_turnsTheTendencyValuesBackIntoPlusAndMinus() {
        GradeScale german = scale(true, null);
        german.tendencyStep = new BigDecimal("0.3");
        assertEquals("2-", german.format(new BigDecimal("2.30")));
        assertEquals("2+", german.format(new BigDecimal("1.70")));
        assertEquals("2", german.format(new BigDecimal("2.00")));
        assertEquals("2,5", german.format(new BigDecimal("2.50")));
        // The Fach's choice to show tendencies as numbers.
        assertEquals("2,3", german.format(new BigDecimal("2.30"), false));

        german.tendencyStep = null;
        assertEquals("2,3", german.format(new BigDecimal("2.30")));
    }

    private static GradeScale scale(boolean lowerIsBetter, String atRiskFrom) {
        GradeScale scale = new GradeScale();
        scale.lowerIsBetter = lowerIsBetter;
        scale.atRiskFrom = atRiskFrom != null ? new BigDecimal(atRiskFrom) : null;
        return scale;
    }
}
