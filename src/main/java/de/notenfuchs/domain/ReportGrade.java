package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A report-card grade ("Zeugnisnote") the teacher set by hand for one student in one Fach. It
 * replaces the computed final grade wherever a final grade counts (grid, Notenübersicht, export),
 * but never changes the computation: the computed grade stays visible next to it.
 */
@Entity
@Table(name = "report_grade", uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "subject_id", "period"}))
public class ReportGrade extends PanacheEntity {

    /** The 2nd half has no report card of its own; the year's report uses {@code YEAR}. */
    public enum Period { FIRST_HALF, YEAR }

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    public Student student;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    public Subject subject;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Period period;

    @NotNull
    @Column(precision = 4, scale = 2, nullable = false)
    public BigDecimal value;

    /** Student id → value, for one Fach and period. */
    public static Map<Long, BigDecimal> bySubject(Long subjectId, Period period) {
        List<ReportGrade> grades = list("subject.id = ?1 and period = ?2", subjectId, period);
        Map<Long, BigDecimal> byStudent = new HashMap<>();
        for (ReportGrade grade : grades) {
            byStudent.put(grade.student.id, grade.value);
        }
        return byStudent;
    }
}
