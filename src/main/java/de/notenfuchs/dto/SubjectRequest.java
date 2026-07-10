package de.notenfuchs.dto;

import de.notenfuchs.domain.RoundingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class SubjectRequest {

    @NotNull
    public Long schoolClassId;

    @NotBlank
    public String name;

    @NotNull
    public Long gradeScaleId;

    public RoundingMode roundingMode;
}
