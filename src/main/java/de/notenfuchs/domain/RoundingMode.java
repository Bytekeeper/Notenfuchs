package de.notenfuchs.domain;

/**
 * Determines how a raw (decimal) subject average is rounded to a whole-number final grade.
 */
public enum RoundingMode {
    /**
     * Standard "kaufmaennisch" (commercial) half-up rounding, purely numeric:
     * x.50 always rounds UP to the next higher number (e.g. 2.50 -> 3), regardless
     * of whether higher numbers mean better or worse grades on the underlying scale.
     */
    COMMERCIAL,

    /**
     * Rounds the exact half (x.50) toward whichever whole number is better for the
     * student, according to the grade scale's lowerIsBetter flag. All other (non-half)
     * values round to the nearest whole number the same way as COMMERCIAL.
     */
    IN_FAVOR_OF_STUDENT
}
