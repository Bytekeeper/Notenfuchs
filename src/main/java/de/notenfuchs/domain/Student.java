package de.notenfuchs.domain;

import java.time.LocalDate;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "student")
public class Student extends PanacheEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_class_id", nullable = false)
    public SchoolClass schoolClass;

    @NotBlank
    @Column(nullable = false)
    public String name;

    /**
     * Optional short/display name (e.g. for roster views). May be null, in which case
     * consumers should fall back to {@link #name}.
     */
    @Column(name = "display_name")
    public String displayName;

    /** The name to actually show in UI/exports - {@link #displayName} if set, otherwise {@link #name}. */
    /**
     * The day the student left the class ("ausgetreten"), or null while still in it. A student
     * who left keeps their grades and is shown read-only, but no longer counts in column
     * averages or "offen" counts.
     */
    @Column(name = "left_on")
    public LocalDate leftOn;

    public boolean hasLeft() {
        return leftOn != null;
    }

    public String effectiveName() {
        return displayName != null && !displayName.isBlank() ? displayName : name;
    }
}
