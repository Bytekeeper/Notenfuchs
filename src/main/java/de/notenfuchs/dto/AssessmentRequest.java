package de.notenfuchs.dto;

import de.notenfuchs.domain.RoundingMode;
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

    public boolean pointsBased;

    /** Only meaningful while {@link #pointsBased} is true; null falls back to {@code IN_FAVOR_OF_STUDENT}. */
    public RoundingMode roundingMode;
}
