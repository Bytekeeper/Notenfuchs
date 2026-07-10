package de.notenfuchs.dto;

import jakarta.validation.constraints.NotBlank;

public class SchoolClassRequest {

    @NotBlank
    public String name;

    @NotBlank
    public String schoolYear;
}
