package de.notenfuchs.service;

import java.math.BigDecimal;

/**
 * Plain in-memory representation of a single grade value plus the factor of the
 * assessment it belongs to. Used as input to {@link GradeService} so the calculation
 * logic can be unit-tested without a running database.
 *
 * @param value  the grade value (on whatever scale the subject uses)
 * @param factor the weighting factor of the assessment this grade belongs to (default 1.0)
 */
public record GradeData(BigDecimal value, BigDecimal factor) {

    public GradeData {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (factor == null) {
            factor = BigDecimal.ONE;
        }
    }

    public GradeData(BigDecimal value) {
        this(value, BigDecimal.ONE);
    }
}
