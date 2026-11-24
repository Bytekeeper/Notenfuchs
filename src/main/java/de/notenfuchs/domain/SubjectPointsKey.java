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
 * One row of a Fach's Notenschlüssel: {@link #gradeValue} from {@link #minPercent} of a test's
 * maximum points. Only a template: a Leistung copies it into its own steps, so a later change
 * doesn't touch tests already written.
 */
@Entity
@Table(name = "subject_points_key", uniqueConstraints = @UniqueConstraint(columnNames = {"subject_id", "grade_value"}))
public class SubjectPointsKey extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    public Subject subject;

    @NotNull
    @Column(name = "grade_value", precision = 4, scale = 2, nullable = false)
    public BigDecimal gradeValue;

    @NotNull
    @Column(name = "min_percent", precision = 5, scale = 2, nullable = false)
    public BigDecimal minPercent;
}
