package de.notenfuchs.service;

import java.math.BigDecimal;

/**
 * Plain in-memory representation of one Notenschlüssel anchor: {@code points} raw points
 * maps exactly to {@code gradeValue}, with points between, below, or above two anchors
 * interpolated/extrapolated linearly (see {@link PointsConversionService#convert}). Used as
 * input to that service so the conversion logic can be unit-tested without a running database.
 *
 * @param points     the points value this anchor applies at
 * @param gradeValue the grade value (on whatever scale the subject uses) this anchor resolves to
 */
public record PointsGradeBandData(BigDecimal points, BigDecimal gradeValue) {

    public PointsGradeBandData {
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
        if (gradeValue == null) {
            throw new IllegalArgumentException("gradeValue must not be null");
        }
    }
}
