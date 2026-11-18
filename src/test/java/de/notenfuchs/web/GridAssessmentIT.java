package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP-level coverage for adding/editing/deleting a Leistung from the grade table
 * ({@code GradeGridResource#addAssessment} and friends). The browser flow is in
 * {@code GradeGridAssessmentE2EIT}; this covers the redirect target and that a category or
 * Leistung of a different Fach can't be reached through this Fach's address.
 */
@QuarkusTest
class GridAssessmentIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void addRedirectsToTheGridFocusedOnTheNewColumn() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse-" + unique);
        Long subjectId = createSubject(classId, "GA-Fach-" + unique);
        Long categoryId = createCategory(subjectId, "GA-Kat-" + unique);

        HttpResponse<String> response = post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "KA1", "factor", "1"));

        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        assertEquals(303, response.statusCode());
        assertEquals("/subjects/" + subjectId + "/grid?focus=" + assessmentId,
                response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void categoryOrLeistungOfAnotherFach_isNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse2-" + unique);
        Long subjectA = createSubject(classId, "GA-FachA-" + unique);
        Long subjectB = createSubject(classId, "GA-FachB-" + unique);
        Long categoryB = createCategory(subjectB, "GA-KatB-" + unique);
        post("/subjects/" + subjectB + "/grid/assessments", Map.of("categoryId", categoryB.toString(), "name", "KA-B"));
        Long assessmentB = Assessment.find("category.id", categoryB).<Assessment>firstResult().id;

        assertEquals(404, post("/subjects/" + subjectA + "/grid/assessments",
                Map.of("categoryId", categoryB.toString(), "name", "KA-falsch")).statusCode());
        assertEquals(404, post("/subjects/" + subjectA + "/grid/assessments/" + assessmentB,
                Map.of("name", "umbenannt", "factor", "1")).statusCode());
        assertEquals(404, post("/subjects/" + subjectA + "/grid/assessments/" + assessmentB + "/delete", Map.of())
                .statusCode());
        assertEquals("KA-B", Assessment.<Assessment>findById(assessmentB).name);
    }

    private Long createClass(String name) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", "2025/26"));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }

    private Long createCategory(Long subjectId, String name) throws Exception {
        post("/subjects/" + subjectId + "/categories", Map.of("name", name, "weightPercent", "100"));
        return GradeCategory.find("name", name).<GradeCategory>firstResult().id;
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
