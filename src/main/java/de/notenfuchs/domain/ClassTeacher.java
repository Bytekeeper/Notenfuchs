package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Marks {@link #teacherSubject} as a full co-owner (Klassenlehrer-tier) of {@link #schoolClass}:
 * identical rights to every other owner, no hierarchy between them. A class can have several of
 * these rows. See {@link de.notenfuchs.security.OwnershipGuard} for what ownership grants versus
 * plain class access (which a teacher gets automatically by teaching any {@link Subject} in the
 * class, via {@link SubjectTeacher}, without needing a row here).
 */
@Entity
@Table(name = "class_teacher")
public class ClassTeacher extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_class_id", nullable = false)
    public SchoolClass schoolClass;

    @NotBlank
    @Column(name = "teacher_subject", nullable = false)
    public String teacherSubject;

    /**
     * View-time-only label ({@link Teacher#displayLabel()} for this row's {@link #teacherSubject},
     * looked up from the {@link Teacher} directory) - not persisted, batch-attached by whichever
     * resource method builds a list of these for rendering (see {@code
     * ClassUiResource#classTeachersResponse}) so the template can do a plain property access
     * instead of an N+1 query or a Map lookup. Null until attached, or if the subject has no
     * {@link Teacher} row (falls back to displaying {@link #teacherSubject} in that case).
     */
    @Transient
    public String resolvedLabel;
}
