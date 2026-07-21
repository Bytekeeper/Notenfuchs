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
        for (Student student : students) {
            Map<Long, StudentSubjectAverageResponse> studentAverages =
                    bySubjectThenStudent.getOrDefault(student.id, Map.of());
            List<CellView> cells = new ArrayList<>();
            for (Subject subject : subjects) {
                StudentSubjectAverageResponse average = studentAverages.get(subject.id);
                cells.add(new CellView(
                        average != null ? average.rawAverage : null,
                        average != null ? average.finalGrade : null));
            }
            rows.add(new RowView(student.id, student.effectiveName(), cells));
        }

        return currentUser.withUser(overviewTemplate
                .data("schoolClass", schoolClass)
                .data("subjects", subjects)
                .data("rows", rows)
                .data("gridEmpty", subjects.isEmpty()));
    }

    // ---- View models for the Qute template ----

    /** One student's row - {@code cells} is aligned index-for-index with the {@code subjects} header list. */
    public record RowView(Long studentId, String studentName, List<CellView> cells) {
    }

    /** {@code null} fields mean no grade entered yet for that (student, Subject) - rendered as "-". */
    public record CellView(BigDecimal rawAverage, Integer finalGrade) {
    }
}
