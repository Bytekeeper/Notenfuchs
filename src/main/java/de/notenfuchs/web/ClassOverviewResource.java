package de.notenfuchs.web;

import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.dto.StudentSubjectAverageResponse;
import de.notenfuchs.rest.ClassAveragesResource;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only, ADMIN-tier-only class-wide grade overview (students x every Subject in the class,
 * final grade + raw average) - the "See all final grades of Fächer and final grade of students"
 * capability from the three-tier access model (see {@link OwnershipGuard#isAdmin}). Deliberately
 * shallow: unlike {@link GradeGridResource}, there's no drill-down into a Subject's categories,
 * Leistungen, or individual Grade rows here - an admin who doesn't personally teach a Subject
 * still can't reach {@code /subjects/{id}} (still {@link OwnershipGuard#requireTeachesSubject}-
 * gated, unchanged), only this aggregate table.
 *
 * <p>Reuses {@link ClassAveragesResource#averages(Long)} directly (a plain in-JVM method call on
 * an injected CDI bean, not HTTP) rather than recomputing the per-student-per-subject average -
 * that method's own {@code isAdmin}-vs-{@code teachesSubject} row filtering already returns every
 * Subject once it's confirmed the caller is an admin, which {@link #overview} confirms first via
 * {@link OwnershipGuard#requireClassAdmin}.
 */
@Path("/classes/{id}/overview")
public class ClassOverviewResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    ClassAveragesResource classAveragesResource;

    @Inject
    @Location("GridPage/classOverview.html")
    Template overviewTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance overview(@PathParam("id") Long id) {
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
        List<Subject> subjects = Subject.list("schoolClass.id = ?1 order by name", id);
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        List<StudentSubjectAverageResponse> averages = classAveragesResource.averages(id);

        Map<Long, Map<Long, StudentSubjectAverageResponse>> bySubjectThenStudent = new HashMap<>();
        for (StudentSubjectAverageResponse average : averages) {
            bySubjectThenStudent
                    .computeIfAbsent(average.studentId, k -> new HashMap<>())
                    .put(average.subjectId, average);
        }

        List<RowView> rows = new ArrayList<>();
        int studentsAtRisk = 0;
        for (Student student : students) {
            Map<Long, StudentSubjectAverageResponse> studentAverages =
                    bySubjectThenStudent.getOrDefault(student.id, Map.of());
            List<CellView> cells = new ArrayList<>();
            int atRiskCount = 0;
            for (Subject subject : subjects) {
                StudentSubjectAverageResponse average = studentAverages.get(subject.id);
                Integer finalGrade = average != null ? average.finalGrade : null;
                boolean atRisk = subject.gradeScale.isAtRisk(finalGrade);
                if (atRisk) {
                    atRiskCount++;
                }
                cells.add(new CellView(average != null ? average.rawAverage : null, finalGrade, atRisk));
            }
            if (atRiskCount > 0) {
                studentsAtRisk++;
            }
            rows.add(new RowView(student.id, student.effectiveName(), cells, atRiskCount));
        }

        // What is still missing before the Zeugniskonferenz, per Fach and aligned with the columns.
        Map<Long, Long> openGrades = GradeGridResource.openGradeCounts(subjects);
        Map<Long, Long> behaviorEntered = GradeGridResource.countsById(Subject.getEntityManager().createQuery(
                "select b.subject.id, count(b) from BehaviorGrade b where b.subject.schoolClass.id = :id group by b.subject.id",
                Object[].class).setParameter("id", id).getResultList());
        List<SubjectStatus> subjectStatus = new ArrayList<>();
        for (Subject subject : subjects) {
            subjectStatus.add(new SubjectStatus(openGrades.getOrDefault(subject.id, 0L),
                    students.size() - behaviorEntered.getOrDefault(subject.id, 0L)));
        }

        return currentUser.withUser(overviewTemplate
                .data("schoolClass", schoolClass)
                .data("subjects", subjects)
                .data("rows", rows)
                .data("studentsAtRisk", studentsAtRisk)
                .data("anyAtRiskScale", subjects.stream().anyMatch(s -> s.gradeScale.atRiskFrom != null))
                .data("subjectStatus", subjectStatus)
                .data("gridEmpty", subjects.isEmpty())
                .data("isClassAdmin", true));
    }

    // ---- View models for the Qute template ----

    /** One student's row - {@code cells} is aligned index-for-index with the {@code subjects} header list. */
    public record RowView(Long studentId, String studentName, List<CellView> cells, int atRiskCount) {
    }

    /** Per Fach (aligned with {@code subjects}): grades still missing for due Leistungen, and missing Verhaltensnoten. */
    public record SubjectStatus(long openGrades, long openBehaviorGrades) {
    }

    /** {@code null} fields mean no grade entered yet for that (student, Subject) - rendered as "-". */
    public record CellView(BigDecimal rawAverage, Integer finalGrade, boolean atRisk) {
    }
}
