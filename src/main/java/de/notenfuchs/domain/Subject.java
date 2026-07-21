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
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Entity
@Table(name = "subject")
public class Subject extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_class_id", nullable = false)
    public SchoolClass schoolClass;

    @NotBlank
    @Column(nullable = false)
    public String name;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grade_scale_id", nullable = false)
    public GradeScale gradeScale;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false)
    public RoundingMode roundingMode = RoundingMode.IN_FAVOR_OF_STUDENT;

    /**
     * View-time-only ({@code ClassUiResource#attachTeacherLabels}) - the resolved {@link
     * Teacher#displayLabel()} of this Subject's first {@link SubjectTeacher}, or {@code null} if
     * it somehow has none. {@code fragments/subjectList.html} shows this inline so a class admin
     * can see who to contact for a Fach, and which teachers have class access only by teaching a
     * Subject (not via a {@link ClassTeacher} row) - cross-reference against the "Lehrkräfte"
     * section, which only lists the latter.
     */
    @Transient
    public String primaryTeacherLabel;

    /** The rest of this Subject's teachers beyond {@link #primaryTeacherLabel}, shown collapsed. */
    @Transient
    public List<String> otherTeacherLabels = List.of();
}
