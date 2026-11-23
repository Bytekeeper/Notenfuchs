package de.notenfuchs.web;

import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import de.notenfuchs.domain.Teacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the read-only "Fachlehrer mit Klassenzugriff" section on the class's "Lehrkräfte" tab
 * ({@link ClassUiResource#transitiveTeachers}) - surfaces which teachers can see the whole class
 * (roster, Verhaltensnoten) purely by teaching one of its Subjects, holding no {@link ClassTeacher}
 * row of their own, since {@link SubjectTeacher} additions are self-service and never need the
 * class admin's approval (see the Authorization section in CLAUDE.md). ADMIN-only, same gating as
 * the neighboring "Lehrkräfte" section. A Failsafe IT (./mvnw verify) - needs a real Postgres,
 * same reasoning as {@link ClassTeacherIT}.
 */
@QuarkusTest
class TransitiveTeacherIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void teachersPage_listsSubjectTeacherWithNoClassTeacherRow() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Transitiv-Klasse-" + unique, "2025/26");
        Long subjectId = createSubject(classId, "Transitiv-Fach-" + unique);
        String colleagueLabel = "kollege-transitiv-" + unique;
        seedTeacher("colleague-transitiv-" + unique, colleagueLabel);
        http.post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", "colleague-transitiv-" + unique));

        String html = http.get("/classes/" + classId + "/teachers").body();

        assertTrue(html.contains("Fachlehrer mit Klassenzugriff"));
        assertTrue(html.contains(colleagueLabel));
    }

    @Test
    void teachersPage_omitsDevUserWhoHasBothAClassTeacherRowAndTeachesTheSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Transitiv-Admin-Klasse-" + unique, "2025/26");
        // dev-user creates the class (ClassTeacher ADMIN row) and the Fach (SubjectTeacher row) -
        // holding a ClassTeacher row means dev-user must not show up as "transitive".
        createSubject(classId, "Transitiv-Admin-Fach-" + unique);

        String html = http.get("/classes/" + classId + "/teachers").body();

        assertTrue(html.contains("Fachlehrer mit Klassenzugriff"));
        assertTrue(html.contains("Noch kein Fach dieser Klasse hat eine Lehrkraft"));
    }

    @Test
    void teachersPage_isNotFoundAndTabHiddenForNonAdminViewer() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass schoolClass = new SchoolClass();
            schoolClass.name = "Transitiv-NonAdmin-Klasse-" + unique;
            schoolClass.schoolYear = "2025/26";
            schoolClass.persist();
            GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            Subject subject = new Subject();
            subject.schoolClass = schoolClass;
            subject.name = "Transitiv-NonAdmin-Fach-" + unique;
            subject.gradeScale = gradeScale;
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            // dev-user teaches this Subject but holds no ClassTeacher row, so it has class access
            // without being an admin - the tab must neither be reachable nor linked.
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "dev-user";
            subjectTeacher.persist();
            return schoolClass.id;
        });

        assertEquals(404, http.get("/classes/" + classId + "/teachers").statusCode());
        assertFalse(http.get("/classes/" + classId).body().contains("/classes/" + classId + "/teachers"));
    }

    private String seedTeacher(String subject, String label) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.displayName = label;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
        return subject;
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }
}
