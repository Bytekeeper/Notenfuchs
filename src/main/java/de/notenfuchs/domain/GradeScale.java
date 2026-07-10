package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Defines a grading scale, e.g. the German "1-6" scale (1 = best, 6 = worst)
 * or a hypothetical points-based scale (e.g. 0-15 "Punkte", where higher is better).
 *
 * Grade values themselves are stored as plain NUMERIC/BigDecimal on {@link Grade},
 * never as an enum - that is precisely what allows new scales to be introduced later
 * (by inserting a new GradeScale row) without any schema migration.
 */
@Entity
@Table(name = "grade_scale")
public class GradeScale extends PanacheEntity {

    @NotBlank
    @Column(nullable = false, unique = true)
    public String name;

    @NotNull
    @Column(nullable = false, precision = 4, scale = 2)
    public BigDecimal min;

    @NotNull
    @Column(nullable = false, precision = 4, scale = 2)
    public BigDecimal max;

    /**
     * true if a numerically lower value is the better grade (e.g. German 1-6 scale,
     * where 1 is the best possible grade). false if a numerically higher value is better
     * (e.g. a 0-15 "Punkte" scale).
     */
    @NotNull
    @Column(name = "lower_is_better", nullable = false)
    public boolean lowerIsBetter;
}
