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

import java.math.BigDecimal;

/**
 * A weighted category of grades within a subject, e.g. "Schriftlich" (written, 50%)
 * or "Muendlich" (oral, 50%).
 */
@Entity
@Table(name = "grade_category")
public class GradeCategory extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    public Subject subject;

    @NotBlank
    @Column(nullable = false)
    public String name;

    @NotNull
    @Column(name = "weight_percent", nullable = false, precision = 5, scale = 2)
    public BigDecimal weightPercent;
}
