package de.notenfuchs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-Fach student page ({@code GradeGridResource#studentPage}): Leistungen, averages, "Was braucht ...". */
@QuarkusTest
class StudentPageIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void showsLeistungenAveragesAndWhatTheNextLeistungNeeds() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.annaId(), "3");

        String html = get("/subjects/" + f.subjectId() + "/grid/students/" + f.annaId()).body();

        assertTrue(html.contains("Anna-"), html);
        assertTrue(html.contains("<td>KA</td>"));
        // Subject rounds COMMERCIAL: with one 3, a 2 needs an average below 2.5, so 1.9 or better.
        assertTrue(html.contains("1.9 oder besser"), html);
        assertTrue(html.contains("nicht erreichbar"));
    }

    @Test
    void showsAnAbsenceNextToTheLeistung() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.benId(), "e");

        String html = get("/subjects/" + f.subjectId() + "/grid/students/" + f.benId()).body();

        assertTrue(html.contains("abwesend, entschuldigt"));
    }

    @Test
    void studentOfAnotherClass_isNotFound() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();

        assertEquals(404, get("/subjects/" + f.subjectId() + "/grid/students/" + other.annaId()).statusCode());
    }

    private record Fixture(Long subjectId, Long assessmentId, Long annaId, Long benId) {
    }

    private Fixture fixture() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String className = "Abs-Klasse-" + unique;
        post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", className).<SchoolClass>firstResult().id;
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        post("/classes/" + classId + "/subjects",
                Map.of("name", "Abs-Fach-" + unique, "gradeScaleId", gradeScaleId.toString(), "roundingMode", "COMMERCIAL"));
        Long subjectId = Subject.find("name", "Abs-Fach-" + unique).<Subject>firstResult().id;
        post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        post("/classes/" + classId + "/students", Map.of("name", "Ben-" + unique));
        post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        post("/subjects/" + subjectId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA",
                "date", LocalDate.now().minusDays(1).toString()));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        Long annaId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        Long benId = Student.find("name", "Ben-" + unique).<Student>firstResult().id;
        return new Fixture(subjectId, assessmentId, annaId, benId);
    }

    private JsonNode saveCell(Fixture f, Long studentId, String value) throws Exception {
        HttpResponse<String> response = post("/subjects/" + f.subjectId + "/grid/cell", Map.of(
                "studentId", studentId.toString(), "assessmentId", f.assessmentId.toString(), "value", value));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        String url = rootUrl.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
