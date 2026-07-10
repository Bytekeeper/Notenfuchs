package de.notenfuchs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class GradeCategoryRequest {

    @NotNull
    public Long subjectId;

    @NotBlank
    public String name;

    @NotNull
    public BigDecimal weightPercent;
}
