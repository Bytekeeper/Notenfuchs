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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single graded event within a category, e.g. a specific test or quiz.
 * The {@link #factor} determines how strongly this assessment's grades are weighted
 * within the weighted mean of its category (default 1.0).
 *
 * <p>If {@link #pointsBased} is true, students are graded by raw points instead of a direct
 * grade value: {@link Grade#points} holds the entered points and the actual grade is derived
 * live from those points + this assessment's {@link PointsGradeBand}s via
 * {@link de.notenfuchs.service.PointsConversionService} - never frozen, so editing the points
 * or the bands always recomputes the grade (see ROADMAP.md's anti-freeze design principle).
 * There's no upfront "max points" to configure - each band's {@link PointsGradeBand#minPoints}
 * is just an absolute points threshold the teacher sets to match their own test. {@link #roundingMode}
 * governs how that derived grade is rounded to one decimal - only meaningful while
 * {@link #pointsBased} is true, mirroring the choice {@code Subject#roundingMode} offers for
 * the whole-grade average rounding.
 */
@Entity
@Table(name = "assessment")
public class Assessment extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    public GradeCategory category;

    @NotBlank
    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public LocalDate date;

    @NotNull
    @Column(nullable = false, precision = 5, scale = 2)
    public BigDecimal factor = BigDecimal.ONE;

    @Column(name = "points_based", nullable = false)
    public boolean pointsBased = false;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false)
    public RoundingMode roundingMode = RoundingMode.IN_FAVOR_OF_STUDENT;
}
