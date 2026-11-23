package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.ReportGrade;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.GradeService;
import de.notenfuchs.service.PointsConversionService;
import de.notenfuchs.service.PointsGradeBandData;
import de.notenfuchs.service.SubjectAverageResult;
import de.notenfuchs.web.GradeGridResource.CategoryColumns;
import de.notenfuchs.web.GradeGridResource.GradeKey;
import de.notenfuchs.web.GradeGridResource.HalfSplit;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.notenfuchs.web.GradeGridResource.gradesByKey;
import static de.notenfuchs.web.GermanNumbers.display;

/**
 * The "Notenspiegel" tab of a Fach: per Leistung, how many active students got each whole grade,
 * plus the same for the Zeugnisnoten. "2-" counts as 2 and points count by their rounded grade,
 * the same rounding as the Notenschlüssel preview.
 */
@Path("/subjects/{id}/distribution")
public class GradeDistributionResource {

    private final GradeService gradeService = new GradeService();
    private final PointsConversionService pointsConversionService = new PointsConversionService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    GradeGridResource grid;

    @Inject
    SubjectUiResource subjectUi;

    @Inject
    @Location("SubjectPage/distribution.html")
    Template distributionTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance distribution(@PathParam("id") Long id) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        GradeScale scale = subject.gradeScale;
        List<Student> students = Student.list("schoolClass.id = ?1 and leftOn is null order by name",
                subject.schoolClass.id);
        List<CategoryColumns> columns = new ArrayList<>();
        List<Assessment> assessments = new ArrayList<>();
        for (GradeCategory category : GradeCategory.<GradeCategory>list("subject.id = ?1 order by id", id)) {
            List<Assessment> categoryAssessments = Assessment.list("category.id", category.id);
            columns.add(new CategoryColumns(category, categoryAssessments));
            assessments.addAll(categoryAssessments);
        }
        Map<GradeKey, Grade> grades = gradesByKey(Grade.list("assessment.category.subject.id", id));
        Map<Long, List<PointsGradeBandData>> bands = grid.bandsByAssessment(assessments);

        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        List<Group> groups = new ArrayList<>();
        List<Row> reportRows = new ArrayList<>();
        if (cutoff == null) {
            groups.add(new Group(null, null, leistungRows(flatten(columns), students, subject, grades, bands)));
        } else {
            HalfSplit split = grid.splitByHalf(columns, cutoff);
            groups.add(new Group("1. Halbjahr", null, leistungRows(flatten(split.h1()), students, subject, grades, bands)));
            groups.add(new Group("2. Halbjahr", null, leistungRows(flatten(split.h2()), students, subject, grades, bands)));
            List<Assessment> undated = flatten(split.undated());
            if (!undated.isEmpty()) {
                groups.add(new Group("Ohne Datum", null, leistungRows(undated, students, subject, grades, bands)));
            }
            reportRows.add(reportRow("1. Halbjahr", split.h1(), ReportGrade.Period.FIRST_HALF, students, subject, grades));
        }
        reportRows.add(reportRow("Jahr", columns, ReportGrade.Period.YEAR, students, subject, grades));
        groups.add(new Group("Zeugnisnoten", "(von Hand festgelegt, sonst berechnet)", reportRows));

        return currentUser.withUser(distributionTemplate
                .data("subject", subject)
                .data("switchSubjects", subjectUi.switchSubjects(subject))
                .data("gradeColumns", scale.wholeGradesBestFirst())
                .data("colspan", scale.wholeGradesBestFirst().size() + 4)
                .data("groups", groups)
                .data("empty", assessments.isEmpty()));
    }

    private static List<Assessment> flatten(List<CategoryColumns> columns) {
        List<Assessment> assessments = new ArrayList<>();
        columns.forEach(cc -> assessments.addAll(cc.assessments()));
        assessments.sort(Comparator.comparing((Assessment a) -> a.date, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(a -> a.id));
        return assessments;
    }

    private List<Row> leistungRows(List<Assessment> assessments, List<Student> students, Subject subject,
                                   Map<GradeKey, Grade> grades, Map<Long, List<PointsGradeBandData>> bands) {
        List<Row> rows = new ArrayList<>();
        for (Assessment assessment : assessments) {
            List<BigDecimal> values = new ArrayList<>();
            for (Student student : students) {
                Grade grade = grades.get(new GradeKey(assessment.id, student.id));
                if (grade == null) {
                    continue;
                }
                BigDecimal value = !assessment.pointsBased ? grade.value : grade.points == null ? null
                        : pointsConversionService.convert(grade.points, bands.get(assessment.id),
                                subject.gradeScale, assessment.roundingMode);
                if (value != null) {
                    values.add(value);
                }
            }
            rows.add(row(assessment.name, assessment.category.name, assessment.date, values, subject));
        }
        return rows;
    }

    /** Each student's Zeugnisnote if set, else the computed final grade of that period. */
    private Row reportRow(String label, List<CategoryColumns> columns, ReportGrade.Period period,
                          List<Student> students, Subject subject, Map<GradeKey, Grade> grades) {
        List<SubjectAverageResult> computed = grid.subjectAveragesForBlock(columns, students, subject, grades);
        Map<Long, BigDecimal> setByHand = ReportGrade.bySubject(subject.id, period);
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            BigDecimal value = setByHand.get(students.get(i).id);
            Integer finalGrade = computed.get(i).finalGrade();
            if (value == null && finalGrade != null) {
                value = BigDecimal.valueOf(finalGrade);
            }
            if (value != null) {
                values.add(value);
            }
        }
        return row(label, null, null, values, subject);
    }

    private Row row(String name, String categoryName, LocalDate date, List<BigDecimal> values, Subject subject) {
        GradeScale scale = subject.gradeScale;
        Map<Integer, Integer> countByGrade = new HashMap<>();
        for (BigDecimal value : values) {
            Integer whole = gradeService.calculateAssessmentAverage(List.of(value), scale, subject.roundingMode).finalGrade();
            countByGrade.merge(whole, 1, Integer::sum);
        }
        List<Integer> counts = scale.wholeGradesBestFirst().stream().map(g -> countByGrade.getOrDefault(g, 0)).toList();
        BigDecimal average = gradeService.calculateAssessmentAverage(values, scale, subject.roundingMode).rawAverage();
        return new Row(name, categoryName, date, counts, average != null ? display(average) : null, values.size());
    }

    // ---- View models for the Qute template ----

    /** {@code title} is null for the Leistungen of a class without half-year cutoff. */
    public record Group(String title, String note, List<Row> rows) {
    }

    /** {@code counts} is aligned with the template's {@code gradeColumns}. */
    public record Row(String name, String categoryName, LocalDate date, List<Integer> counts, String average,
                      int gradeCount) {
    }
}
