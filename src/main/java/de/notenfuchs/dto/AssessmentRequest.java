package de.notenfuchs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class AssessmentRequest {

    @NotNull
    public Long categoryId;

    @NotBlank
    public String name;

    public LocalDate date;

    public BigDecimal factor;
}
