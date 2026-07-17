package de.notenfuchs.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class BehaviorGradeRequest {

    @NotNull
    public Long studentId;

    @NotNull
    public Long subjectId;

    @NotNull
    public BigDecimal value;
}
