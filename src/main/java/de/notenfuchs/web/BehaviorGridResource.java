package de.notenfuchs.web;

import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.BehaviorGradeService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verhaltensnoten: a class-wide grid (students as rows, every Fach of the class as columns) for
 * entering one behavior/conduct grade per (student, Fach) - a {@link BehaviorGrade}, deliberately
 * independent of {@link de.notenfuchs.domain.Grade}/{@link GradeService}: it never influences a
 * subject's academic average, it's only its own separate figure on the Halbjahres-/
 * Endjahreszeugnis. There is no H1/H2 split (unlike {@link GradeGridResource}'s Halbjahr view) -
 * a single always-current value the teacher updates before each report is printed, consistent
 * with this app's anti-freeze design principle (see ROADMAP.md).
 *
 * <p>Since a class has exactly one owning teacher (no sharing between teachers, see
 * OwnershipGuard), "your Fächer" is simply every Fach of the class - the grid always shows all of
 * them, not a per-Fach scoped view like {@link GradeGridResource}.
 *
 * <p>The per-Fach column average (bottom row) reuses {@link GradeService#calculateAssessmentAverage}
 * exactly like {@link GradeGridResource}'s per-Leistung footer - a plain average within that one
 * Fach's own {@link de.notenfuchs.domain.GradeScale}, so it gets a proper rounded final grade too.
 * The per-student row average (right column) cannot do the same: a student's Verhaltensnoten span
 * multiple Fächer that may each use a different GradeScale, so there is no single scale to round
 * against - see {@link BehaviorGradeService}'s javadoc for why it only produces a raw average,
 * plus a "borderline" flag (close to a whole-grade rounding boundary) instead of a discrete grade.
 */
@Path("/classes/{id}/behavior-grid")
public class BehaviorGridResource {

    private final GradeService gradeService = new GradeService();
    private final BehaviorGradeService behaviorGradeService = new BehaviorGradeService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    @Location("GridPage/behaviorGrid.html")
    Template gridTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grid(@PathParam("id") Long id) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        GridData data = loadGridData(schoolClass);
        return currentUser.withUser(gridTemplate
                .data("schoolClass", schoolClass)
                .data("subjects", data.subjects())
                .data("rows", data.rows())
                .data("subjectFooters", data.subjectFooters())
                .data("maxCol", data.maxCol())
                .data("maxRow", data.maxRow())
                .data("gridEmpty", data.gridEmpty()));
    }

    private GridData loadGridData(SchoolClass schoolClass) {
        List<Subject> subjects = Subject.list("schoolClass.id = ?1 order by name", schoolClass.id);
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", schoolClass.id);

        List<BehaviorGrade> classGrades = BehaviorGrade.list("subject.schoolClass.id", schoolClass.id);
        Map<GradeKey, BehaviorGrade> grades = new HashMap<>();
        for (BehaviorGrade grade : classGrades) {
            grades.put(new GradeKey(grade.subject.id, grade.student.id), grade);
        }

        List<List<BigDecimal>> columnValues = new ArrayList<>();
        for (int i = 0; i < subjects.size(); i++) {
            columnValues.add(new ArrayList<>());
        }

        List<RowView> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Student student : students) {
            List<CellView> cells = new ArrayList<>();
            List<BigDecimal> studentValues = new ArrayList<>();
            int colIndex = 0;
            for (Subject subject : subjects) {
                BehaviorGrade grade = grades.get(new GradeKey(subject.id, student.id));
                String displayValue = grade != null ? plain(grade.value) : null;
                if (grade != null) {
                    studentValues.add(grade.value);
                    columnValues.get(colIndex).add(grade.value);
                }
                cells.add(new CellView(colIndex, subject.id, displayValue));
                colIndex++;
            }
            BigDecimal rowAverage = behaviorGradeService.average(studentValues);
            boolean borderline = behaviorGradeService.isBorderline(rowAverage);
            rows.add(new RowView(rowIndex, new StudentView(student.id, student.effectiveName()),
                    cells, rowAverage, borderline));
            rowIndex++;
        }

        List<SubjectFooter> subjectFooters = new ArrayList<>();
        for (int i = 0; i < subjects.size(); i++) {
            Subject subject = subjects.get(i);
            SubjectAverageResult average = gradeService.calculateAssessmentAverage(
                    columnValues.get(i), subject.gradeScale, subject.roundingMode);
            subjectFooters.add(new SubjectFooter(subject.id, average.rawAverage(), average.finalGrade()));
        }

        int maxCol = Math.max(0, subjects.size() - 1);
        int maxRow = Math.max(0, students.size() - 1);
        boolean gridEmpty = subjects.isEmpty();

        return new GridData(subjects, rows, subjectFooters, maxCol, maxRow, gridEmpty);
    }

    @POST
    @Path("/cell")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveCell(@PathParam("id") Long id,
                              @FormParam("studentId") Long studentId,
                              @FormParam("subjectId") Long subjectId,
                              @FormParam("value") String rawValue) {
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentSubject);
        Student student = guard.requireOwnedStudent(studentId, currentSubject);
        Subject subject = guard.requireOwnedSubject(subjectId, currentSubject);
        if (!student.schoolClass.id.equals(schoolClass.id) || !subject.schoolClass.id.equals(schoolClass.id)) {
            throw new NotFoundException();
        }

        BehaviorGrade existing = BehaviorGrade.find("student.id = ?1 and subject.id = ?2",
                studentId, subjectId).firstResult();

        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.isEmpty()) {
            if (existing != null) {
                existing.delete();
            }
            return Response.ok(cellSaveResponse(student, subject, null)).build();
        }

        BigDecimal enteredValue;
        try {
            enteredValue = new BigDecimal(trimmed.replace(",", "."));
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Ungültiger Notenwert: '" + rawValue + "'").build();
        }

        BigDecimal min = subject.gradeScale.min;
        BigDecimal max = subject.gradeScale.max;
        if (enteredValue.compareTo(min) < 0 || enteredValue.compareTo(max) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Note muss zwischen " + plain(min) + " und " + plain(max) + " liegen")
                    .build();
        }

        if (existing != null) {
            existing.value = enteredValue;
        } else {
            BehaviorGrade grade = new BehaviorGrade();
            grade.student = student;
            grade.subject = subject;
            grade.value = enteredValue;
            grade.persist();
        }

        return Response.ok(cellSaveResponse(student, subject, enteredValue)).build();
    }

    /**
     * Recomputes both averages touched by this cell edit: the edited Fach's column average
     * (across every student, within that one Fach's scale) and the edited student's row average
     * (across every Fach the student has a Verhaltensnote in, scale-agnostic - see
     * {@link BehaviorGradeService}).
     */
    private CellSaveResponse cellSaveResponse(Student student, Subject subject, BigDecimal enteredValue) {
        List<BehaviorGrade> subjectGrades = BehaviorGrade.list("subject.id", subject.id);
        List<BigDecimal> subjectValues = subjectGrades.stream().map(g -> g.value).toList();
        SubjectAverageResult subjectAverage = gradeService.calculateAssessmentAverage(
                subjectValues, subject.gradeScale, subject.roundingMode);

        List<BehaviorGrade> studentGrades = BehaviorGrade.list("student.id", student.id);
        List<BigDecimal> studentValues = studentGrades.stream().map(g -> g.value).toList();
        BigDecimal studentAverage = behaviorGradeService.average(studentValues);
        boolean studentBorderline = behaviorGradeService.isBorderline(studentAverage);

        String displayValue = enteredValue != null ? plain(enteredValue) : "";
        String studentRawAverageStr = studentAverage != null ? plain(studentAverage) : null;
        String subjectRawAverageStr = subjectAverage.rawAverage() != null ? plain(subjectAverage.rawAverage()) : null;

        return new CellSaveResponse(displayValue, studentRawAverageStr, studentBorderline,
                subject.id, subjectRawAverageStr, subjectAverage.finalGrade());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ---- View models for the Qute template ----

    /** Lookup key indexing a class's Verhaltensnoten by (subject, student). */
    private record GradeKey(Long subjectId, Long studentId) {
    }

    private record GridData(List<Subject> subjects, List<RowView> rows,
                             List<SubjectFooter> subjectFooters, int maxCol, int maxRow, boolean gridEmpty) {
    }

    public record StudentView(Long id, String effectiveName) {
    }

    public record CellView(int colIndex, Long subjectId, String displayValue) {
    }

    public record RowView(int rowIndex, StudentView student, List<CellView> cells,
                           BigDecimal rawAverage, boolean borderline) {
    }

    /** One Fach's average Verhaltensnote across every student who has one, shown in the grid footer. */
    public record SubjectFooter(Long subjectId, BigDecimal rawAverage, Integer finalGrade) {
    }

    public record CellSaveResponse(String displayValue,
                                    String studentRawAverage, boolean studentBorderline,
                                    Long subjectId, String subjectRawAverage, Integer subjectFinalGrade) {
    }
}
