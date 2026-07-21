package de.notenfuchs.domain;

/**
 * The privilege tier of a {@link ClassTeacher} row. {@code ADMIN} is today's full co-owner
 * (roster, class settings, admin/Fachlehrer-tier management, delete any Subject, read-only
 * class-wide grade overview). {@code FACHLEHRER} is the class-level tier below it: can add a new
 * Subject and delete/manage Subjects they personally teach, but not administer the class itself -
 * see {@link de.notenfuchs.security.OwnershipGuard#isAdmin}/{@link
 * de.notenfuchs.security.OwnershipGuard#isClassTeacher}.
 */
public enum ClassTeacherRole {
    ADMIN,
    FACHLEHRER;

    /** German display label, used wherever this enum is rendered to a teacher. */
    @Override
    public String toString() {
        return switch (this) {
            case ADMIN -> "Admin (Klassenlehrer)";
            case FACHLEHRER -> "Fachlehrer (Klassenebene)";
        };
    }
}
