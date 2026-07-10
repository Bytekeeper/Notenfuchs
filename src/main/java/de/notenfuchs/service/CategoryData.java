package de.notenfuchs.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Plain in-memory representation of one grade category (e.g. "Schriftlich") for a single
 * student: its weight within the subject, and the list of that student's grades in this
 * category. An empty {@code grades} list means the student has no grades yet in this
 * category - such categories are excluded from the weighted subject average (see
 * {@link GradeService#calculateSubjectAverage}).
 *
 * @param weightPercent the weight of this category within the subject (e.g. 50 for 50%)
 * @param grades        this student's grades within the category
 */
public record CategoryData(BigDecimal weightPercent, List<GradeData> grades) {

    public CategoryData {
        if (weightPercent == null) {
            throw new IllegalArgumentException("weightPercent must not be null");
        }
        if (grades == null) {
            grades = List.of();
        }
    }

    public boolean hasGrades() {
        return !grades.isEmpty();
    }
}
