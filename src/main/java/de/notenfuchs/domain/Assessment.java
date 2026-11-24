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
import java.time.LocalDate;

/**
 * A single graded event within a category, e.g. a specific test or quiz.
 * The {@link #factor} determines how strongly this assessment's grades are weighted
 * within the weighted mean of its category (default 1.0).
 *
 * <p>If {@link #pointsBased} is true, {@link Grade#points} holds the points and the grade is
 * derived live from this assessment's {@link PointsGradeBand} rows via
 * {@link de.notenfuchs.service.PointsConversionService}; nothing converted is stored.
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

    /** Only set while {@link #pointsBased}: the Fach's percentages turn into points against it. */
    @Column(name = "max_points", precision = 6, scale = 2)
    public BigDecimal maxPoints;

    /** The Notenschlüssel's rows are read sliding (the grade moves evenly between them), else as steps. */
    @Column(name = "points_sliding", nullable = false)
    public boolean pointsSliding;
}
