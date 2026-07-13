package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A single grade value for one student on one assessment.
 *
 * The value is stored as a plain NUMERIC(4,2) / BigDecimal - deliberately NOT an enum -
 * so that switching or adding grading scales (e.g. a future 0-15 "Punkte" scale) never
 * requires a schema migration. Interpretation of the value (what counts as "best",
 * valid range, etc.) is entirely delegated to the Subject's associated GradeScale.
 *
 * <p>Exactly one of {@link #value} / {@link #points} is set, driven by whether the assessment
 * is {@link Assessment#pointsBased}: a direct-entry assessment sets {@link #value} directly,
 * while a points-based one sets {@link #points} (the raw points entered) and leaves
 * {@link #value} null - the actual grade is derived live from the points plus the assessment's
 * {@link PointsGradeBand}s rather than stored, so it never goes stale (see
 * {@link de.notenfuchs.service.PointsConversionService}).
 */
@Entity
@Table(name = "grade")
public class Grade extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false)
    public Assessment assessment;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    public Student student;

    @Column(precision = 4, scale = 2)
    public BigDecimal value;

    @Column(precision = 6, scale = 2)
    public BigDecimal points;
}
