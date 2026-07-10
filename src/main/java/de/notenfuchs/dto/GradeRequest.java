package de.notenfuchs.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class GradeRequest {

    @NotNull
    public Long assessmentId;

    @NotNull
    public Long studentId;

    @NotNull
    public BigDecimal value;
}
