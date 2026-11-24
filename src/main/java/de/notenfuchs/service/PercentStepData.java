package de.notenfuchs.service;

import java.math.BigDecimal;

/** One row of a Fach's Notenschlüssel: the grade starts at {@code minPercent} of a test's maximum points. */
public record PercentStepData(BigDecimal gradeValue, BigDecimal minPercent) {
}
