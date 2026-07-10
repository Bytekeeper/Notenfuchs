package de.notenfuchs.service;

import java.math.BigDecimal;

/**
 * Result of a subject average calculation for one student.
 *
 * @param rawAverage the weighted average, rounded to 2 decimal places for display
 *                    (intermediate computation uses higher precision internally)
 * @param finalGrade the raw average rounded to a whole number according to the
 *                    subject's rounding mode, or {@code null} if there were no grades
 *                    at all (rawAverage is also {@code null} in that case)
 */
public record SubjectAverageResult(BigDecimal rawAverage, Integer finalGrade) {

    public static final SubjectAverageResult EMPTY = new SubjectAverageResult(null, null);
}
