package de.notenfuchs.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines a grading scale, e.g. the German "1-6" scale (1 = best, 6 = worst)
 * or a hypothetical points-based scale (e.g. 0-15 "Punkte", where higher is better).
 *
 * Grade values themselves are stored as plain NUMERIC/BigDecimal on {@link Grade},
 * never as an enum - that is precisely what allows new scales to be introduced later
 * (by inserting a new GradeScale row) without any schema migration.
 */
@Entity
@Table(name = "grade_scale")
public class GradeScale extends PanacheEntity {

    @NotBlank
    @Column(nullable = false, unique = true)
    public String name;

    @NotNull
    @Column(nullable = false, precision = 4, scale = 2)
    public BigDecimal min;

    @NotNull
    @Column(nullable = false, precision = 4, scale = 2)
    public BigDecimal max;

    /**
     * true if a numerically lower value is the better grade (e.g. German 1-6 scale,
     * where 1 is the best possible grade). false if a numerically higher value is better
     * (e.g. a 0-15 "Punkte" scale).
     */
    @NotNull
    @Column(name = "lower_is_better", nullable = false)
    public boolean lowerIsBetter;

    /** A final grade this bad or worse puts the student at risk (e.g. 5 on DE 1-6); null = no signal. */
    @Column(name = "at_risk_from", precision = 4, scale = 2)
    public BigDecimal atRiskFrom;

    /** How far "2-" / "2+" lie from the whole grade (e.g. 0.3); null = no +/- entry on this scale. */
    @Column(name = "tendency_step", precision = 4, scale = 2)
    public BigDecimal tendencyStep;

    /**
     * Reads a typed grade: "2,3", "2.3", or with a tendency step also "2-" / "2+". "+" always means
     * the better side, so its direction follows {@link #lowerIsBetter}. Doesn't check the range.
     *
     * @throws NumberFormatException if the text is no grade
     */
    public BigDecimal parseGrade(String text) {
        String number = text.trim().replace(",", ".");
        char last = number.isEmpty() ? ' ' : number.charAt(number.length() - 1);
        if (tendencyStep == null || number.length() < 2 || (last != '+' && last != '-')) {
            return new BigDecimal(number);
        }
        BigDecimal whole = new BigDecimal(number.substring(0, number.length() - 1).trim());
        if (whole.stripTrailingZeros().scale() > 0) {
            throw new NumberFormatException("A tendency only follows a whole grade: " + text);
        }
        boolean better = last == '+';
        return better == lowerIsBetter ? whole.subtract(tendencyStep) : whole.add(tendencyStep);
    }

    /** The reverse of {@link #parseGrade}: "2-" for 2.3 on a scale with step 0.3, else the number with a decimal comma. */
    public String format(BigDecimal value) {
        if (tendencyStep != null) {
            BigDecimal below = value.setScale(0, java.math.RoundingMode.FLOOR);
            BigDecimal above = value.setScale(0, java.math.RoundingMode.CEILING);
            if (value.subtract(below).compareTo(tendencyStep) == 0) {
                return below.toPlainString() + (lowerIsBetter ? "-" : "+");
            }
            if (above.subtract(value).compareTo(tendencyStep) == 0) {
                return above.toPlainString() + (lowerIsBetter ? "+" : "-");
            }
        }
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Every whole grade of the scale, best first: the columns of a Notenspiegel. */
    public List<Integer> wholeGradesBestFirst() {
        int best = lowerIsBetter ? min.intValue() : max.intValue();
        int worst = lowerIsBetter ? max.intValue() : min.intValue();
        int step = best <= worst ? 1 : -1;
        List<Integer> grades = new ArrayList<>();
        for (int g = best; g != worst + step; g += step) {
            grades.add(g);
        }
        return grades;
    }

    public boolean contains(BigDecimal value) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    public boolean isAtRisk(Integer finalGrade) {
        if (finalGrade == null || atRiskFrom == null) {
            return false;
        }
        int comparison = BigDecimal.valueOf(finalGrade).compareTo(atRiskFrom);
        return lowerIsBetter ? comparison >= 0 : comparison <= 0;
    }
}
