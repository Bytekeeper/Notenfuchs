package de.notenfuchs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notenfuchs.domain.Absence;
import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The absence mark typed into a grade cell ("e" / "u", alone or next to the grade): it is stored
 * next to the grade, never changes an average, and an absence without a grade is not "offen".
 */
@QuarkusTest
class GradeAbsenceIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void absenceAlone_isStoredWithoutGradeAndIsNotOpen() throws Exception {
        Fixture f = fixture();

        JsonNode response = saveCell(f, f.annaId, "e");

        assertEquals("e", response.get("displayValue").asText());
        assertTrue(response.get("rawAverage").isNull());
        Grade grade = Grade.find("student.id", f.annaId).firstResult();
        assertEquals(Absence.EXCUSED, grade.absence);
        assertNull(grade.value);

        String html = get("/subjects/" + f.subjectId + "/grid").body();
        assertTrue(html.contains("value=\"e\""));
        // Ben has nothing yet, Anna is absent: only one cell counts as missing.
        assertEquals(1, html.split("grade-cell-open", -1).length - 1, html);
    }

    @Test
    void absenceNextToAGrade_keepsTheGradeInTheAverage() throws Exception {
        Fixture f = fixture();

        JsonNode response = saveCell(f, f.annaId, "6 u");

        assertEquals("6 u", response.get("displayValue").asText());
        assertEquals("6", response.get("rawAverage").asText());
        assertEquals(Absence.UNEXCUSED, Grade.<Grade>find("student.id", f.annaId).firstResult().absence);
    }

    @Test
    void switchingToPoints_clearsGradesButKeepsAbsences() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.annaId, "4 e");
        saveCell(f, f.benId, "2");

        post("/subjects/" + f.subjectId + "/grid/assessments/" + f.assessmentId,
                Map.of("name", "KA", "factor", "1", "pointsBased", "true"));

        Grade anna = Grade.find("student.id", f.annaId).firstResult();
        assertEquals(Absence.EXCUSED, anna.absence);
        assertNull(anna.value);
        assertEquals(0, Grade.count("student.id", f.benId));
    }

    @Test
    void export_writesAnAbsenceWithoutGradeAsItsLetter() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.annaId, "u");
        saveCell(f, f.benId, "3");

        byte[] xlsx = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/subjects/" + f.subjectId + "/grid/export")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean foundLetter = false;
            boolean foundNumber = false;
            for (var row : sheet) {
                for (var cell : row) {
                    foundLetter |= cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && cell.getStringCellValue().equals("u");
                    foundNumber |= cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            && cell.getNumericCellValue() == 3.0;
                }
            }
            assertTrue(foundLetter);
            assertTrue(foundNumber);
        }
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
