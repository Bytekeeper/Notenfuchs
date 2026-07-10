package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.service.CategoryData;
import de.notenfuchs.service.GradeData;
import de.notenfuchs.service.GradeService;
import de.notenfuchs.service.SubjectAverageResult;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The headline feature: an Excel-like grade-entry grid for a subject (students as rows,
 * assessments as columns, grouped by category), with per-cell autosave and a live
 * per-student average column driven by {@link GradeService}.
 *
 * <p>The page itself ({@link #grid(Long)}) is a normal full HTML render. Cell edits are
 * persisted via {@link #saveCell} - a small JSON endpoint called from
 * {@code static/js/notenfuchs.js} (plain fetch, not an HTMX swap, since we need
 * fine-grained keyboard-driven navigation that HTMX's declarative triggers don't fit well).
 */
@Path("/subjects/{id}/grid")
public class GradeGridResource {

    private final GradeService gradeService = new GradeService();

    @Inject
    CurrentUser currentUser;

    @Inject
    @Location("GridPage/grid.html")
    Template gridTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grid(@PathParam("id") Long id) {
        Subject subject = findSubjectOrNotFound(id);

        List<GradeCategory> categories = GradeCategory.list("subject.id", id);
        List<Student> students = Student.list("schoolClass.id", subject.schoolClass.id);

        List<CategoryColumns> categoryColumns = new ArrayList<>();
        List<Assessment> allAssessments = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            categoryColumns.add(new CategoryColumns(category, assessments));
            allAssessments.addAll(assessments);
        }

        List<RowView> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Student student : students) {
            List<CellView> cells = new ArrayList<>();
            List<CategoryData> categoryDataList = new ArrayList<>();
            int colIndex = 0;
            for (CategoryColumns cc : categoryColumns) {
                boolean categoryStart = true;
                List<GradeData> gradeDataList = new ArrayList<>();
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                            assessment.id, student.id).firstResult();
                    String displayValue = grade != null ? plain(grade.value) : null;
                    cells.add(new CellView(colIndex, assessment.id, categoryStart, displayValue));
                    if (grade != null) {
                        gradeDataList.add(new GradeData(grade.value, assessment.factor));
                    }
                    categoryStart = false;
                    colIndex++;
                }
                categoryDataList.add(new CategoryData(cc.category().weightPercent, gradeDataList));
            }

            SubjectAverageResult average = gradeService.calculateSubjectAverage(
                    categoryDataList, subject.gradeScale, subject.roundingMode);

            rows.add(new RowView(rowIndex, new StudentView(student.id, effectiveName(student)),
                    cells, average.rawAverage(), average.finalGrade()));
            rowIndex++;
        }

        int maxCol = Math.max(0, allAssessments.size() - 1);
        int maxRow = Math.max(0, rows.size() - 1);
        boolean gridEmpty = categoryColumns.isEmpty() || allAssessments.isEmpty();

        return withUser(gridTemplate
                .data("subject", subject)
                .data("categories", categoryColumns)
                .data("students", students)
                .data("rows", rows)
                .data("maxCol", maxCol)
                .data("maxRow", maxRow)
                .data("gridEmpty", gridEmpty));
    }

    @POST
    @Path("/cell")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveCell(@PathParam("id") Long id,
                              @FormParam("studentId") Long studentId,
                              @FormParam("assessmentId") Long assessmentId,
                              @FormParam("value") String rawValue) {
        Subject subject = findSubjectOrNotFound(id);
        Student student = Student.findById(studentId);
        if (student == null) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        Assessment assessment = Assessment.findById(assessmentId);
        if (assessment == null) {
            throw new NotFoundException("Assessment " + assessmentId + " not found");
        }

        Grade existing = Grade.find("assessment.id = ?1 and student.id = ?2", assessmentId, studentId).firstResult();

        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.isEmpty()) {
            if (existing != null) {
                existing.delete();
            }
            return Response.ok(averagePayload(subject, student, null)).build();
        }

        BigDecimal value;
        try {
            // Accept German comma decimals too, in case a client bypasses the JS normalization.
            value = new BigDecimal(trimmed.replace(",", "."));
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Ungültiger Notenwert: '" + rawValue + "'")
                    .build();
        }

        BigDecimal min = subject.gradeScale.min;
        BigDecimal max = subject.gradeScale.max;
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Note muss zwischen " + plain(min) + " und " + plain(max) + " liegen")
                    .build();
        }

        if (existing != null) {
            existing.value = value;
        } else {
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = student;
            grade.value = value;
            grade.persist();
        }

        return Response.ok(averagePayload(subject, student, value)).build();
    }

    private CellSaveResponse averagePayload(Subject subject, Student student, BigDecimal savedValue) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<CategoryData> categoryDataList = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            List<GradeData> gradeDataList = new ArrayList<>();
            for (Assessment assessment : assessments) {
                Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                        assessment.id, student.id).firstResult();
                if (grade != null) {
                    gradeDataList.add(new GradeData(grade.value, assessment.factor));
                }
            }
            categoryDataList.add(new CategoryData(category.weightPercent, gradeDataList));
        }
        SubjectAverageResult average = gradeService.calculateSubjectAverage(
                categoryDataList, subject.gradeScale, subject.roundingMode);

        String displayValue = savedValue != null ? plain(savedValue) : "";
        String rawAverageStr = average.rawAverage() != null ? plain(average.rawAverage()) : null;
        return new CellSaveResponse(displayValue, rawAverageStr, average.finalGrade());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String effectiveName(Student student) {
        return student.displayName != null && !student.displayName.isBlank() ? student.displayName : student.name;
    }

    private Subject findSubjectOrNotFound(Long id) {
        Subject entity = Subject.findById(id);
        if (entity == null) {
            throw new NotFoundException("Subject " + id + " not found");
        }
        return entity;
    }

    private TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", currentUser.isAuthenticated())
                .data("currentUserDisplayName", currentUser.displayName().orElse(""));
    }

    // ---- View models for the Qute template ----

    public record CategoryColumns(GradeCategory category, List<Assessment> assessments) {
    }

    public record StudentView(Long id, String effectiveName) {
    }

    public record CellView(int colIndex, Long assessmentId, boolean categoryStart, String displayValue) {
    }

    public record RowView(int rowIndex, StudentView student, List<CellView> cells,
                           BigDecimal rawAverage, Integer finalGrade) {
    }

    public record CellSaveResponse(String displayValue, String rawAverage, Integer finalGrade) {
    }
}
