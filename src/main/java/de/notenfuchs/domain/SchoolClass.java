package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "school_class")
public class SchoolClass extends PanacheEntity {

    @NotBlank
    @Column(nullable = false)
    public String name;

    @NotBlank
    @Column(name = "school_year", nullable = false)
    public String schoolYear;
}
