package de.notenfuchs.domain;

/** Why a student missed a Leistung. Recorded next to the grade; it never changes the grade itself. */
public enum Absence {
    EXCUSED("e"),
    UNEXCUSED("u");

    /** The letter a teacher types into a grade cell (and sees there). */
    public final String code;

    Absence(String code) {
        this.code = code;
    }

    public static Absence fromCode(String code) {
        for (Absence absence : values()) {
            if (absence.code.equalsIgnoreCase(code)) {
                return absence;
            }
        }
        return null;
    }
}
