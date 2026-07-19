package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Marks {@link #teacherSubject} as a teacher (Fachlehrer) of {@link #subject}: gates individual
 * Leistung-level access (categories, assessments, grades, subject config) for that one subject,
 * regardless of whether this teacher also owns the class via {@link ClassTeacher}. A subject can
 * have several of these rows - any current teacher of a subject can add another one directly, no
 * class-owner approval needed (see {@link de.notenfuchs.security.OwnershipGuard}). Having at
 * least one row here for a class's subject is also what grants that teacher plain class-wide
 * access (roster read, subject list, Verhaltensnoten) to the whole class, without a separate row
 * anywhere else - collaborator-level class access is derived, never stored twice.
 */
@Entity
@Table(name = "subject_teacher")
public class SubjectTeacher extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    public Subject subject;

    @NotBlank
    @Column(name = "teacher_subject", nullable = false)
    public String teacherSubject;
}
