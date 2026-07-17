package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A Verhaltensnote: one behavior/conduct grade per (student, subject), entered directly by the
 * teacher rather than derived from any {@link Assessment}. Deliberately independent of
 * {@link Grade} and {@link GradeService} - it never contributes to a subject's academic average,
 * it only appears as its own separate figure on the Halbjahres-/Endjahreszeugnis. There is no
 * H1/H2 split: like every other figure in this app, it's a single always-current value the
 * teacher updates before each report is printed (see ROADMAP.md's anti-freeze design principle).
 *
 * <p>Reuses the owning {@link Subject}'s {@link GradeScale} rather than inventing a separate
 * conduct scale, consistent with this app's scale-agnostic design. Exactly one row exists per
 * (student, subject) pair (enforced by a unique constraint) - editing overwrites it in place.
 */
@Entity
@Table(name = "behavior_grade", uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "subject_id"}))
public class BehaviorGrade extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    public Student student;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    public Subject subject;

    @NotNull
    @Column(precision = 4, scale = 2, nullable = false)
    public BigDecimal value;
}
