package de.notenfuchs.service;

import java.math.BigDecimal;

/** One step of a Leistung's Notenschlüssel: from {@code points} on, the grade is {@code gradeValue}. */
public record PointsGradeBandData(BigDecimal points, BigDecimal gradeValue) {

    public PointsGradeBandData {
        if (points == null || gradeValue == null) {
            throw new IllegalArgumentException("points and gradeValue must not be null");
        }
    }
}
