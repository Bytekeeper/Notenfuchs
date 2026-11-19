package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.notenfuchs.web.GradeGridResource.gradesByKey;
import static de.notenfuchs.web.GradeGridResource.plain;

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
                                        @QueryParam("factor") BigDecimal factor) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        Student student = guard.requireClassAccessStudent(studentId, currentSubject);
        if (!student.schoolClass.id.equals(subject.schoolClass.id)) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
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
                    : assessment.pointsBased ? plain(grade.points) + " Pkt. → " + plain(value) : plain(value);
            leistungen.add(new StudentLeistungView(assessment.category.name, assessment.name, assessment.date,
                    assessment.factor, gradeText, grade != null && grade.absence != null ? grade.absence.label : null));
        }

        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
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
            averages.add(new AverageView("1. Halbjahr", h1Average.rawAverage(), h1Average.finalGrade() != null ? grid.displayLabel(subject, h1Average) : null));
            averages.add(new AverageView("2. Halbjahr", h2Average.rawAverage(), h2Average.finalGrade() != null ? grid.displayLabel(subject, h2Average) : null));
            // A Leistung written today falls into the half that contains today.
            boolean firstHalf = halfYearPartitioner.classify(LocalDate.now(), cutoff) == HalfYearAssessmentPartitioner.Half.FIRST;
            currentHalf = firstHalf ? h1 : h2;
            currentHalfName = firstHalf ? "1. Halbjahr" : "2. Halbjahr";
        }
        SubjectAverageResult yearAverage = average(categories, assessments, grades, student, subject);
        averages.add(new AverageView("Jahr", yearAverage.rawAverage(),
                yearAverage.finalGrade() != null ? yearAverage.finalGrade().toString() : null));

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
                .data("nextFactor", plain(nextFactor))
                .data("currentHalfName", currentHalfName)
                .data("requirements", requirements));
    }

    private static String requirementText(RequiredGradeService.Requirement requirement) {
        if (requirement.unreachable()) {
            return "nicht erreichbar";
        }
        if (requirement.alreadySafe()) {
            return "sicher";
        }
        return plain(requirement.requiredGrade()) + " oder besser";
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

    public record AverageView(String label, BigDecimal rawAverage, String grade) {
    }

    public record RequirementRow(int targetGrade, String currentHalf, String year) {
    }
}
