package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

@Entity
@Table(name = "school_class")
public class SchoolClass extends PanacheEntity {

    @NotBlank
    @Column(nullable = false)
    public String name;

    @NotBlank
    @Column(name = "school_year", nullable = false)
    public String schoolYear;

    /**
     * The OIDC subject ("sub" claim, see {@link de.notenfuchs.security.CurrentUser}) of the
     * teacher who owns this class. This is the ownership root for the whole data model - every
     * other entity (Student, Subject, GradeCategory, Assessment, Grade) is scoped through its
     * {@code SchoolClass} rather than carrying its own owner column. Set once at creation time
     * and never changed.
     */
    @NotBlank
    @Column(name = "owner_subject", nullable = false)
    public String ownerSubject;

    /**
     * Optional link to the {@link SchoolClass} this one was duplicated from - see the
     * "copy class into a new school year" action ({@code ClassUiResource#duplicate}). Purely
     * informational (e.g. future trend features); never used for access control or locking.
     * The FK is {@code ON DELETE SET NULL}: deleting the predecessor must not be blocked by,
     * or cascade into, a class that was only ever derived from it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predecessor_class_id")
    public SchoolClass predecessorClass;

    /**
     * Optional cutoff date splitting the grade grid into "1. Halbjahr" (Assessments dated on or
     * before this date) and "2. Halbjahr" (dated after it) - purely a display/query filter, not
     * a new grading concept: no Halbjahr entity, no snapshot, every average still recomputes
     * live from the same {@code Assessment}/{@code Grade} rows (see ROADMAP.md's anti-freeze
     * design principle). {@code null} (the default) means the grid shows a single full-year view
     * exactly as before this feature existed. See {@link de.notenfuchs.web.GradeGridResource}.
     */
    @Column(name = "half_year_cutoff")
    public LocalDate halfYearCutoff;
}
