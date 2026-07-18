package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One anchor point of a points-based {@link Assessment}'s Notenschlüssel: {@link #points}
 * raw points maps exactly to {@link #gradeValue}, and points falling between, below, or above
 * two anchors interpolate/extrapolate linearly. Bands are freely configurable per assessment -
 * no Bundesland-specific scheme is imposed. See
 * {@link de.notenfuchs.service.PointsConversionService} for how a points value is resolved
 * against these bands.
 */
@Entity
@Table(name = "points_grade_band")
public class PointsGradeBand extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false)
    public Assessment assessment;

    @NotNull
    @Column(name = "points", nullable = false, precision = 6, scale = 2)
    public BigDecimal points;

    @NotNull
    @Column(name = "grade_value", nullable = false, precision = 4, scale = 2)
    public BigDecimal gradeValue;
}
