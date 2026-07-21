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

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the read-only "Fachlehrer mit Klassenzugriff" section on the class page
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

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void classDetail_listsSubjectTeacherWithNoClassTeacherRow() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Transitiv-Klasse-" + unique, "2025/26");
        Long subjectId = createSubject(classId, "Transitiv-Fach-" + unique);
        String colleagueLabel = "kollege-transitiv-" + unique + "@schule.de";
        seedTeacher("colleague-transitiv-" + unique, colleagueLabel);
        post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", "colleague-transitiv-" + unique));

        String html = get("/classes/" + classId).body();

        assertTrue(html.contains("Fachlehrer mit Klassenzugriff"));
        assertTrue(html.contains(colleagueLabel));
    }

    @Test
    void classDetail_omitsDevUserWhoHasBothAClassTeacherRowAndTeachesTheSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Transitiv-Admin-Klasse-" + unique, "2025/26");
        // dev-user creates the class (ClassTeacher ADMIN row) and the Fach (SubjectTeacher row) -
        // holding a ClassTeacher row means dev-user must not show up as "transitive".
        createSubject(classId, "Transitiv-Admin-Fach-" + unique);

        String html = get("/classes/" + classId).body();

        assertTrue(html.contains("Fachlehrer mit Klassenzugriff"));
        assertTrue(html.contains("Keine."));
    }

    @Test
    void classDetail_hidesSectionForNonAdminViewer() throws Exception {
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
            // without being an admin - the new section must not render for it at all.
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "dev-user";
            subjectTeacher.persist();
            return schoolClass.id;
        });

        String html = get("/classes/" + classId).body();

        assertFalse(html.contains("Fachlehrer mit Klassenzugriff"));
    }

    private String seedTeacher(String subject, String email) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.email = email;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
        return subject;
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String baseUrl() {
        String url = rootUrl.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
