package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * A directory row for every teacher who has made at least one authenticated request against this
 * instance - kept fresh by {@link de.notenfuchs.security.TeacherDirectoryRecorder}. This is shared,
 * unowned data (same category as {@link GradeScale}) rather than something {@link
 * de.notenfuchs.security.OwnershipGuard}-scoped: any authenticated teacher can read the full list,
 * since it's what lets a class owner pick a colleague from a plain select when adding a co-owner
 * (see {@code ClassUiResource#addClassTeacher}) instead of needing an invite-link/token flow.
 *
 * <p>{@link #subject} is the same OIDC "sub" (or the fixed local-auth/dev-mode subject) used
 * throughout {@link ClassTeacher}/{@link SubjectTeacher} - deliberately kept a bare string there,
 * not a foreign key to this table, so "who has access" and "who's known to exist" stay decoupled.
 */
@Entity
@Table(name = "teacher")
public class Teacher extends PanacheEntity {

    @NotBlank
    @Column(nullable = false, unique = true)
    public String subject;

    /** Best-effort, refreshed on every sighting - see {@link de.notenfuchs.security.CurrentUser#email()}. */
    public String email;

    @Column(name = "display_name")
    public String displayName;

    @NotNull
    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    @NotNull
    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;

    /** The label to show in UI (e.g. the add-teacher dropdown) - prefers {@link #email}, then {@link #displayName}, then {@link #subject}. */
    public String displayLabel() {
        if (email != null && !email.isBlank()) {
            return email;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return subject;
    }
}
