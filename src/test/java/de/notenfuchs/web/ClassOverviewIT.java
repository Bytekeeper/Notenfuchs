package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@link ClassOverviewResource} - the ADMIN-tier-only, read-only
 * class-wide grade overview ("See all final grades of Fächer and final grade of students" from
 * the three-tier access model). A Failsafe IT (./mvnw verify) - needs a real Postgres, same
 * reasoning as {@link ClassTeacherIT}. Every request acts as the fixed "dev-user" subject (see
 * {@code CurrentUser#effectiveSubject()} under %test); non-admin scenarios seed a *foreign* class
 * and attach dev-user to it only as far as the scenario needs, same pattern as
 * {@link SubjectStructuralAccessIT}.
 */
@QuarkusTest
class ClassOverviewIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void overview_admin_showsFinalGradesForEveryStudentAndSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Overview-Klasse-" + unique, "2025/26");
        String subjectAName = "Overview-FachA-" + unique;
        String subjectBName = "Overview-FachB-" + unique;
        Long subjectAId = createSubject(classId, subjectAName);
        createSubject(classId, subjectBName);
        String studentName = "Overview-Schueler-" + unique;
        Long studentId = createStudent(classId, studentName);

        // A single graded Leistung in FachA only - FachB stays ungraded ("-").
        QuarkusTransaction.requiringNew().run(() -> {
            Subject subjectA = Subject.<Subject>findById(subjectAId);
            Student student = Student.<Student>findById(studentId);
            GradeCategory category = new GradeCategory();
            category.subject = subjectA;
            category.name = "Schriftlich";
            category.weightPercent = new BigDecimal("100");
            category.persist();
            Assessment assessment = new Assessment();
            assessment.category = category;
            assessment.name = "Klassenarbeit 1";
            assessment.factor = BigDecimal.ONE;
            assessment.persist();
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = student;
            grade.value = new BigDecimal("2");
            grade.persist();
        });

        HttpResponse<String> response = get("/classes/" + classId + "/overview");

        assertEquals(200, response.statusCode());
        String html = response.body();
        assertTrue(html.contains(studentName));
        assertTrue(html.contains(subjectAName));
        assertTrue(html.contains(subjectBName));
        assertTrue(html.contains(">2<"));
    }

    @Test
    void overview_classLevelFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Fremd-Fachlehrer-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            ClassTeacher classFachlehrer = new ClassTeacher();
            classFachlehrer.schoolClass = foreign;
            classFachlehrer.teacherSubject = "dev-user";
            classFachlehrer.role = ClassTeacherRole.FACHLEHRER;
            classFachlehrer.persist();
            return foreign.id;
        });

        HttpResponse<String> response = get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
    }

    @Test
    void overview_subjectOnlyFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Fremd-Subjektlehrer-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "Overview-Fremd-Fach-" + unique;
            subject.gradeScale = gradeScale;
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "dev-user";
            subjectTeacher.persist();
            return foreign.id;
        });

        HttpResponse<String> response = get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
    }

    @Test
    void overview_foreignClass_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Komplett-Fremd-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            return foreign.id;
        });

        HttpResponse<String> response = get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
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

    private Long createStudent(Long classId, String name) throws Exception {
        post("/classes/" + classId + "/students", Map.of("name", name, "displayName", ""));
        return Student.find("name", name).<Student>firstResult().id;
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
