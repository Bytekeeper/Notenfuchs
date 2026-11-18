package de.notenfuchs.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static GradeScale scale(boolean lowerIsBetter, String atRiskFrom) {
        GradeScale scale = new GradeScale();
        scale.lowerIsBetter = lowerIsBetter;
        scale.atRiskFrom = atRiskFrom != null ? new BigDecimal(atRiskFrom) : null;
        return scale;
    }
}
