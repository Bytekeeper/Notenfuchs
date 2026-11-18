package de.notenfuchs.domain;

/** Why a student missed a Leistung. Recorded next to the grade; it never changes the grade itself. */
public enum Absence {
    EXCUSED("e", "abwesend, entschuldigt"),
    UNEXCUSED("u", "abwesend, unentschuldigt");

    /** The letter a teacher types into a grade cell (and sees there). */
    public final String code;
    public final String label;

    Absence(String code, String label) {
        this.code = code;
        this.label = label;
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
