package de.notenfuchs.web;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the "Meine Fächer" section on the home page. Uses school years that sort
 * after every other IT's ("9999/..."), since the section shows only the viewer's newest year and
 * the test DB is shared across ITs.
 */
@QuarkusTest
class MySubjectsIT {

    private static final String NEWEST_YEAR = "9999/00";
    private static final String OLDER_YEAR = "9998/99";

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void showsOnlySubjectsTheViewerTeaches_evenInAClassTheyAdminister() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Mine-Klasse-" + unique, NEWEST_YEAR);
        Long taughtId = createSubject(classId, "Mine-Fach-" + unique);
        seedSubjectTaughtByColleague(classId, "Mine-Fremdfach-" + unique, "colleague-" + unique);

        String html = get("/classes").body();

        assertTrue(html.contains("Meine Fächer"));
        assertTrue(html.contains("href=\"/subjects/" + taughtId + "/grid\""));
        assertTrue(html.contains("Mine-Klasse-" + unique + " – Mine-Fach-" + unique));
        assertFalse(html.contains("Mine-Fremdfach-" + unique));
    }

    @Test
    void showsOnlyTheNewestSchoolYear() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long oldClassId = createClass("Alt-Klasse-" + unique, OLDER_YEAR);
        createSubject(oldClassId, "Alt-Fach-" + unique);
        Long newClassId = createClass("Neu-Klasse-" + unique, NEWEST_YEAR);
        createSubject(newClassId, "Neu-Fach-" + unique);

        String html = get("/classes").body();

        assertTrue(html.contains("Neu-Fach-" + unique));
        assertFalse(html.contains("Alt-Fach-" + unique));
    }

    private void seedSubjectTaughtByColleague(Long classId, String name, String colleagueSubject) {
        QuarkusTransaction.requiringNew().run(() -> {
            Subject subject = new Subject();
            subject.schoolClass = SchoolClass.findById(classId);
            subject.name = name;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = colleagueSubject;
            subjectTeacher.persist();
        });
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
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
