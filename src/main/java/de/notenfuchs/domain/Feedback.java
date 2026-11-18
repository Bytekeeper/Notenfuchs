package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A bug report or suggestion sent from the feedback dialog. Holds only the kind of page it came
 * from ({@code /subjects/{id}/grid}, never the ids or anything shown on it), the message and the
 * time - not who sent it.
 */
@Entity
@Table(name = "feedback")
public class Feedback extends PanacheEntity {

    @Column(nullable = false, length = 200)
    public String page;

    @Column(nullable = false, length = 2000)
    public String message;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
