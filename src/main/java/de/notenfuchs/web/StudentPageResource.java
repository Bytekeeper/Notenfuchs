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
import de.notenfuchs.service.CategoryData;
import de.notenfuchs.service.GradeData;
import de.notenfuchs.service.GradeService;
import de.notenfuchs.service.HalfYearAssessmentPartitioner;
import de.notenfuchs.service.RequiredGradeService;
import de.notenfuchs.service.SubjectAverageResult;
import de.notenfuchs.web.GradeGridResource.GradeKey;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.notenfuchs.web.GradeGridResource.formatOrNull;
import static de.notenfuchs.web.GradeGridResource.gradesByKey;
import static de.notenfuchs.web.GermanNumbers.display;

/**
 * The page of one student in one Fach, opened from a student's name in the grade grid. Reuses the
 * grid's grade conversion and half-year labels (injected {@link GradeGridResource}) so both pages
 * always show the same numbers.
 */
@Path("/subjects/{id}/grid/students")
public class StudentPageResource {

    private final GradeService gradeService = new GradeService();
    private final HalfYearAssessmentPartitioner halfYearPartitioner = new HalfYearAssessmentPartitioner();
    private final RequiredGradeService requiredGradeService = new RequiredGradeService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    GradeGridResource grid;

    @Inject
    SubjectUiResource subjectUi;

    @Inject
    @Location("GridPage/student.html")
    Template studentTemplate;

    /**
     * One student in this Fach: every Leistung with its grade, the averages, and what the next
     * Leistung would need to be for each final grade ({@link RequiredGradeService}). Printable for
     * parent conversations. {@code category}/{@code factor} describe that hypothetical next Leistung.
     */
    @GET
    @Path("/{studentId}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance studentPage(@PathParam("id") Long id,
                                        @PathParam("studentId") Long studentId,
                                        @QueryParam("category") Long categoryId,
                                        @QueryParam("factor") BigDecimal factor,
                                        @QueryParam("reportGradeError") String reportGradeError,
                                        @QueryParam("zeugnisnote") String focusReportGrade) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        Student student = requireStudentOfSubject(studentId, subject, currentSubject);
        List<GradeCategory> categories = GradeCategory.list("subject.id = ?1 order by id", id);
        List<Assessment> assessments = Assessment.list(
                "category.subject.id = ?1 order by category.id, date nulls last, id", id);
        Map<GradeKey, Grade> grades = gradesByKey(
                Grade.list("student.id = ?1 and assessment.category.subject.id = ?2", studentId, id));

        List<StudentLeistungView> leistungen = new ArrayList<>();
        for (Assessment assessment : assessments) {
            Grade grade = grades.get(new GradeKey(assessment.id, studentId));
            BigDecimal value = grade != null ? grid.effectiveGradeValue(grade, assessment, subject.gradeScale) : null;
            String gradeText = value == null ? "–"
                    : assessment.pointsBased ? display(grade.points) + " Pkt. → " + subject.formatGrade(value)
                    : subject.formatGrade(value);
            leistungen.add(new StudentLeistungView(assessment.category.name, assessment.name, assessment.date,
                    assessment.factor, gradeText, grade != null && grade.absence != null ? grade.absence.label : null));
        }

        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        String firstHalfReportGrade = formatOrNull(subject, reportGrade(subject, student, ReportGrade.Period.FIRST_HALF));
        String yearReportGrade = formatOrNull(subject, reportGrade(subject, student, ReportGrade.Period.YEAR));
        List<AverageView> averages = new ArrayList<>();
        List<Assessment> currentHalf = null;
        String currentHalfName = null;
        if (cutoff != null) {
            List<Assessment> h1 = new ArrayList<>();
            List<Assessment> h2 = new ArrayList<>();
            for (Assessment assessment : assessments) {
                switch (halfYearPartitioner.classify(assessment.date, cutoff)) {
                    case FIRST -> h1.add(assessment);
                    case SECOND -> h2.add(assessment);
                    case UNDATED -> { }
                }
            }
            SubjectAverageResult h1Average = average(categories, h1, grades, student, subject);
            SubjectAverageResult h2Average = average(categories, h2, grades, student, subject);
            averages.add(new AverageView("1. Halbjahr", h1Average.rawAverage(),
                    h1Average.finalGrade() != null ? grid.displayLabel(subject, h1Average) : null, firstHalfReportGrade));
            averages.add(new AverageView("2. Halbjahr", h2Average.rawAverage(),
                    h2Average.finalGrade() != null ? grid.displayLabel(subject, h2Average) : null, null));
            // A Leistung written today falls into the half that contains today.
            boolean firstHalf = halfYearPartitioner.classify(LocalDate.now(), cutoff) == HalfYearAssessmentPartitioner.Half.FIRST;
            currentHalf = firstHalf ? h1 : h2;
            currentHalfName = firstHalf ? "1. Halbjahr" : "2. Halbjahr";
        }
        SubjectAverageResult yearAverage = average(categories, assessments, grades, student, subject);
        averages.add(new AverageView("Jahr", yearAverage.rawAverage(),
                yearAverage.finalGrade() != null ? yearAverage.finalGrade().toString() : null, yearReportGrade));

        // A student who left gets no further Leistung, so there is nothing to work out for them.
        GradeCategory nextCategory = student.hasLeft() ? null : categories.stream()
                .filter(c -> c.id.equals(categoryId)).findFirst()
                .orElse(categories.isEmpty() ? null : categories.get(0));
        BigDecimal nextFactor = factor != null && factor.signum() > 0 ? factor : BigDecimal.ONE;
        List<RequirementRow> requirements = new ArrayList<>();
        if (nextCategory != null) {
            int index = categories.indexOf(nextCategory);
            List<RequiredGradeService.Requirement> year = requiredGradeService.requiredGrades(
                    categoryData(categories, assessments, grades, student, subject), index, nextFactor,
                    subject.gradeScale, subject.roundingMode);
            List<RequiredGradeService.Requirement> half = currentHalf == null ? null : requiredGradeService.requiredGrades(
                    categoryData(categories, currentHalf, grades, student, subject), index, nextFactor,
                    subject.gradeScale, subject.roundingMode);
            for (int i = 0; i < year.size(); i++) {
                requirements.add(new RequirementRow(year.get(i).targetGrade(),
                        half != null ? requirementText(half.get(i)) : null, requirementText(year.get(i))));
            }
        }

        return currentUser.withUser(studentTemplate
                .data("subject", subject)
                .data("student", student)
                .data("switchSubjects", subjectUi.switchSubjects(subject))
                .data("leistungen", leistungen)
                .data("averages", averages)
                .data("categories", categories)
                .data("nextCategory", nextCategory)
                .data("nextFactor", nextFactor)
                .data("currentHalfName", currentHalfName)
                .data("requirements", requirements)
                .data("hasHalfYear", cutoff != null)
                .data("firstHalfReportGrade", firstHalfReportGrade)
                .data("yearReportGrade", yearReportGrade)
                .data("reportGradeError", reportGradeMessage(reportGradeError, subject.gradeScale))
                // The grid's ✎ links here with the period whose field should get the cursor.
                .data("focusReportGrade", "firstHalf".equals(focusReportGrade) && cutoff != null ? "firstHalf"
                        : focusReportGrade != null ? "year" : null));
    }

    /**
     * Sets or clears (empty field) the Zeugnisnoten of one student. A plain form POST: on bad input
     * it redirects back with an error code, since the page's averages need the full GET anyway.
     */
    @POST
    @Path("/{studentId}/report-grades")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveReportGrades(@PathParam("id") Long id,
                                     @PathParam("studentId") Long studentId,
                                     @FormParam("firstHalf") String firstHalf,
                                     @FormParam("year") String year) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        Student student = requireStudentOfSubject(studentId, subject, currentSubject);
        if (student.hasLeft()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Schüler ist ausgetreten").build();
        }
        boolean hasHalfYear = subject.schoolClass.halfYearCutoff != null;
        String page = "/subjects/" + id + "/grid/students/" + studentId;

        BigDecimal yearValue;
        BigDecimal firstHalfValue;
        try {
            yearValue = parseReportGrade(year, subject.gradeScale);
            firstHalfValue = hasHalfYear ? parseReportGrade(firstHalf, subject.gradeScale) : null;
        } catch (NumberFormatException e) {
            return Redirects.seeOther(page + "?reportGradeError=invalid#zeugnisnote").build();
        }
        if ((yearValue != null && !subject.gradeScale.contains(yearValue))
                || (firstHalfValue != null && !subject.gradeScale.contains(firstHalfValue))) {
            return Redirects.seeOther(page + "?reportGradeError=range#zeugnisnote").build();
        }
        // "Jahr" is always a whole grade, the same as the computed one.
        if (yearValue != null && yearValue.stripTrailingZeros().scale() > 0) {
            return Redirects.seeOther(page + "?reportGradeError=whole#zeugnisnote").build();
        }

        storeReportGrade(subject, student, ReportGrade.Period.YEAR, yearValue);
        if (hasHalfYear) {
            storeReportGrade(subject, student, ReportGrade.Period.FIRST_HALF, firstHalfValue);
        }
        return Redirects.seeOther(page).build();
    }

    private Student requireStudentOfSubject(Long studentId, Subject subject, String currentSubject) {
        Student student = guard.requireClassAccessStudent(studentId, currentSubject);
        if (!student.schoolClass.id.equals(subject.schoolClass.id)) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        return student;
    }

    private static BigDecimal parseReportGrade(String text, GradeScale scale) {
        return text == null || text.isBlank() ? null : scale.parseGrade(text);
    }

    private static BigDecimal reportGrade(Subject subject, Student student, ReportGrade.Period period) {
        ReportGrade grade = ReportGrade.find("subject.id = ?1 and student.id = ?2 and period = ?3",
                subject.id, student.id, period).firstResult();
        return grade != null ? grade.value : null;
    }

    private static void storeReportGrade(Subject subject, Student student, ReportGrade.Period period, BigDecimal value) {
        ReportGrade existing = ReportGrade.find("subject.id = ?1 and student.id = ?2 and period = ?3",
                subject.id, student.id, period).firstResult();
        if (value == null) {
            if (existing != null) {
                existing.delete();
            }
            return;
        }
        if (existing != null) {
            existing.value = value;
            return;
        }
        ReportGrade grade = new ReportGrade();
        grade.subject = subject;
        grade.student = student;
        grade.period = period;
        grade.value = value;
        grade.persist();
    }

    private static String reportGradeMessage(String code, GradeScale scale) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "range" -> "Die Note muss zwischen " + display(scale.min) + " und " + display(scale.max) + " liegen.";
            case "whole" -> "Die Jahresnote ist immer eine ganze Note.";
            default -> "Bitte eine Note eingeben, zum Beispiel 2 oder 2-.";
        };
    }

    private static String requirementText(RequiredGradeService.Requirement requirement) {
        if (requirement.unreachable()) {
            return "nicht erreichbar";
        }
        if (requirement.alreadySafe()) {
            return "sicher";
        }
        return display(requirement.requiredGrade()) + " oder besser";
    }

    /** The student's grades in {@code assessments}, one {@link CategoryData} per category (empty ones included). */
    private List<CategoryData> categoryData(List<GradeCategory> categories, List<Assessment> assessments,
                                            Map<GradeKey, Grade> grades, Student student, Subject subject) {
        List<CategoryData> result = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<GradeData> categoryGrades = new ArrayList<>();
            for (Assessment assessment : assessments) {
                if (!assessment.category.id.equals(category.id)) {
                    continue;
                }
                Grade grade = grades.get(new GradeKey(assessment.id, student.id));
                BigDecimal value = grade != null ? grid.effectiveGradeValue(grade, assessment, subject.gradeScale) : null;
                if (value != null) {
                    categoryGrades.add(new GradeData(value, assessment.factor));
                }
            }
            result.add(new CategoryData(category.weightPercent, categoryGrades));
        }
        return result;
    }

    private SubjectAverageResult average(List<GradeCategory> categories, List<Assessment> assessments,
                                         Map<GradeKey, Grade> grades, Student student, Subject subject) {
        return gradeService.calculateSubjectAverage(categoryData(categories, assessments, grades, student, subject),
                subject.gradeScale, subject.roundingMode);
    }

    public record StudentLeistungView(String categoryName, String name, LocalDate date, BigDecimal factor,
                                      String gradeText, String absenceLabel) {
    }

    /** {@code reportGrade}: the Zeugnisnote set by hand, shown instead of {@code grade}; null if none. */
    public record AverageView(String label, BigDecimal rawAverage, String grade, String reportGrade) {
    }

    public record RequirementRow(int targetGrade, String currentHalf, String year) {
    }
}
