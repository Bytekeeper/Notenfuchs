package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Subject;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "What is still missing": the rule for when an empty grade cell counts as open, and the grouped
 * counts the home page badge and the Notenübersicht show. Shared by those pages and the grade grid.
 */
final class OpenGrades {

    private OpenGrades() {
    }

    /**
     * Per Fach, how many grades are still missing for Leistungen whose date has come (the same rule
     * as the grid's highlight, {@link #isDue}); a cell with only an absence mark counts as entered.
     * Only Fächer with at least one missing grade are in the map. Grouped queries instead of loading
     * every grade, since the home page and the Notenübersicht call this for many Fächer at once.
     */
    static Map<Long, Long> openGradeCounts(List<Subject> subjects) {
        if (subjects.isEmpty()) {
            return Map.of();
        }
        List<Long> subjectIds = subjects.stream().map(s -> s.id).toList();
        List<Long> classIds = subjects.stream().map(s -> s.schoolClass.id).distinct().toList();
        LocalDate today = LocalDate.now();
        var em = Subject.getEntityManager();
        Map<Long, Long> dueAssessments = countsById(em.createQuery(
                "select a.category.subject.id, count(a) from Assessment a"
                        + " where a.category.subject.id in :ids and a.date <= :today group by a.category.subject.id",
                Object[].class).setParameter("ids", subjectIds).setParameter("today", today).getResultList());
        Map<Long, Long> studentsByClass = countsById(em.createQuery(
                "select s.schoolClass.id, count(s) from Student s where s.schoolClass.id in :ids and s.leftOn is null"
                        + " group by s.schoolClass.id",
                Object[].class).setParameter("ids", classIds).getResultList());
        Map<Long, Long> enteredGrades = countsById(em.createQuery(
                "select g.assessment.category.subject.id, count(g) from Grade g"
                        + " where g.assessment.category.subject.id in :ids and g.assessment.date <= :today"
                        + " and g.student.leftOn is null"
                        + " group by g.assessment.category.subject.id",
                Object[].class).setParameter("ids", subjectIds).setParameter("today", today).getResultList());
        Map<Long, Long> open = new HashMap<>();
        for (Subject s : subjects) {
            long missing = dueAssessments.getOrDefault(s.id, 0L) * studentsByClass.getOrDefault(s.schoolClass.id, 0L)
                    - enteredGrades.getOrDefault(s.id, 0L);
            if (missing > 0) {
                open.put(s.id, missing);
            }
        }
        return open;
    }

    static Map<Long, Long> countsById(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** A Leistung whose date has come; only then is an empty cell worth pointing out. Undated ones never are. */
    static boolean isDue(Assessment assessment, LocalDate today) {
        return assessment.date != null && !assessment.date.isAfter(today);
    }
}
