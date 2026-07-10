package de.notenfuchs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class StudentRequest {

    @NotNull
    public Long schoolClassId;

    @NotBlank
    public String name;

    public String displayName;
}
