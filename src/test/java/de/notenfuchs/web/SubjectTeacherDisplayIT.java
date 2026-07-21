package de.notenfuchs.web;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@code fragments/subjectList.html} showing which teacher(s) teach each
 * Fach ({@link ClassUiResource#attachTeacherLabels}) - lets a class admin see who to contact per
 * Fach, and notice which teachers have class access only via a {@link SubjectTeacher} row (not a
 * {@link de.notenfuchs.domain.ClassTeacher} row, so they're absent from the "Lehrkräfte" section).
 * Shown regardless of whether the viewer personally teaches the Fach. A Failsafe IT
 * (./mvnw verify) - needs a real Postgres, same reasoning as {@link ClassTeacherIT}.
 */
@QuarkusTest
class SubjectTeacherDisplayIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void subjectList_showsSoleTeacherAndNoCollapsibleWhenOnlyOne() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Solo-Klasse-" + unique, "2025/26");
        // dev-user creates the Fach, so is automatically its sole SubjectTeacher (see #addSubject).
        Long subjectId = createSubject(classId, "Display-Solo-Fach-" + unique);

        String html = get("/classes/" + classId).body();

        assertTrue(html.contains("dev-user"));
        assertFalse(html.contains("subject-teachers-more"));
    }

    @Test
    void subjectList_showsFirstTeacherPlusCollapsibleForTheRest_regardlessOfWhetherViewerTeachesIt() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Multi-Klasse-" + unique, "2025/26");
        Long subjectId = createSubject(classId, "Display-Multi-Fach-" + unique);
        String colleagueALabel = "kollegin-a-" + unique + "@schule.de";
        String colleagueBLabel = "kollegin-b-" + unique + "@schule.de";
        seedTeacher(colleagueALabel, colleagueALabel);
        seedTeacher(colleagueBLabel, colleagueBLabel);
        post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueALabel));
        post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueBLabel));

        String html = get("/classes/" + classId).body();

        assertTrue(html.contains("dev-user"));
        assertTrue(html.contains("subject-teachers-more"));
        assertTrue(html.contains("+2 weitere"));
        assertTrue(html.contains(colleagueALabel));
        assertTrue(html.contains(colleagueBLabel));
    }

    @Test
    void subjectList_showsTeacherInfoEvenForSubjectViewerDoesNotTeach() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Untaught-Klasse-" + unique, "2025/26");
        String otherTeacherLabel = "kollegin-untaught-" + unique + "@schule.de";
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass schoolClass = SchoolClass.<SchoolClass>findById(classId);
            GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            Subject subject = new Subject();
            subject.schoolClass = schoolClass;
            subject.name = "Display-Untaught-Fach-" + unique;
            subject.gradeScale = gradeScale;
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "colleague-untaught-" + unique;
            subjectTeacher.persist();
            Teacher teacher = new Teacher();
            teacher.subject = "colleague-untaught-" + unique;
            teacher.email = otherTeacherLabel;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
            return subject.id;
        });

        String html = get("/classes/" + classId).body();

        assertTrue(html.contains("unterrichtet von: " + otherTeacherLabel));
    }

    private void seedTeacher(String subject, String email) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.email = email;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
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
