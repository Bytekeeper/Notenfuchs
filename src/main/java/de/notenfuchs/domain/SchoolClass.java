package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

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
}
