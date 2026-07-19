package de.notenfuchs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@link BehaviorGridResource} (Verhaltensnoten): saving a cell persists
 * a {@link BehaviorGrade} and returns both the edited Fach's column average (via
 * {@link de.notenfuchs.service.GradeService#calculateAssessmentAverage}) and the edited student's
 * row average across all their Fächer, including the "borderline" (close to a x.5 rounding
 * boundary) flag. A Failsafe IT (./mvnw verify) - needs a real Postgres (Testcontainers Dev
 * Services), same reasoning as {@link GradeGridHalfYearIT}.
 */
@QuarkusTest
class BehaviorGridIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cellSave_updatesFachAverageAndStudentAverage_includingBorderlineFlag() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("VN-Klasse-" + unique, "2025/26");
        Long studentA = createStudent(classId, "VN-Schueler-A-" + unique);
        Long studentB = createStudent(classId, "VN-Schueler-B-" + unique);
        Long subjectFachA = createSubject(classId, "VN-FachA-" + unique, gradeScale.id);
        Long subjectFachB = createSubject(classId, "VN-FachB-" + unique, gradeScale.id);

        // Student A gets a 2 in FachA: FachA average = 2, Student A average = 2 (not borderline).
        // The JSON response strips trailing zeros (like GradeGridResource's equivalent payload) -
        // only the initial page render keeps the fixed 2-decimal BigDecimal format.
        JsonNode afterA1 = saveCellAndParse(classId, studentA, subjectFachA, "2");
        assertEquals("2", afterA1.get("displayValue").asText());
        assertEquals("2", afterA1.get("studentRawAverage").asText());
        assertFalse(afterA1.get("studentBorderline").asBoolean());
        assertEquals("2", afterA1.get("subjectRawAverage").asText());
        assertEquals(2, afterA1.get("subjectFinalGrade").asInt());

        // Student A gets a 3 in FachB: Student A average becomes (2+3)/2 = 2.5 -> borderline.
        JsonNode afterA2 = saveCellAndParse(classId, studentA, subjectFachB, "3");
        assertEquals("2.5", afterA2.get("studentRawAverage").asText());
        assertTrue(afterA2.get("studentBorderline").asBoolean());
        assertEquals("3", afterA2.get("subjectRawAverage").asText());

        // Student B gets a 4 in FachA: FachA average across both students = (2+4)/2 = 3.
        // Student B's own average is just 4 - not borderline, and must not affect Student A's.
        JsonNode afterB1 = saveCellAndParse(classId, studentB, subjectFachA, "4");
        assertEquals("4", afterB1.get("studentRawAverage").asText());
        assertFalse(afterB1.get("studentBorderline").asBoolean());
        assertEquals("3", afterB1.get("subjectRawAverage").asText());
        assertEquals(3, afterB1.get("subjectFinalGrade").asInt());
    }

    @Test
    void clearingCell_deletesGradeAndRecomputesAverages() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("VN-Klasse-Clear-" + unique, "2025/26");
        Long studentId = createStudent(classId, "VN-Schueler-Clear-" + unique);
        Long subjectA = createSubject(classId, "VN-FachA-Clear-" + unique, gradeScale.id);
        Long subjectB = createSubject(classId, "VN-FachB-Clear-" + unique, gradeScale.id);

        saveCellAndParse(classId, studentId, subjectA, "2");
        saveCellAndParse(classId, studentId, subjectB, "4");

        JsonNode afterClear = saveCellAndParse(classId, studentId, subjectB, "");
        assertEquals("", afterClear.get("displayValue").asText());
        // Only FachA (2) remains for the student.
        assertEquals("2", afterClear.get("studentRawAverage").asText());
        assertTrue(afterClear.get("subjectRawAverage").isNull());
        assertTrue(afterClear.get("subjectFinalGrade").isNull());

        Long remaining = QuarkusTransaction.requiringNew().call(
                () -> BehaviorGrade.count("subject.id", subjectB));
        assertEquals(0L, remaining);
    }

    @Test
    void valueOutsideGradeScale_returns400_andDoesNotPersist() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("VN-Klasse-Invalid-" + unique, "2025/26");
        Long studentId = createStudent(classId, "VN-Schueler-Invalid-" + unique);
        Long subjectId = createSubject(classId, "VN-Fach-Invalid-" + unique, gradeScale.id);

        HttpResponse<String> response = post("/classes/" + classId + "/behavior-grid/cell", Map.of(
                "studentId", String.valueOf(studentId),
                "subjectId", String.valueOf(subjectId),
                "value", "7"));
        assertEquals(400, response.statusCode());

        Long persisted = QuarkusTransaction.requiringNew().call(
                () -> BehaviorGrade.count("subject.id", subjectId));
        assertEquals(0L, persisted);
    }

    @Test
    void foreignClass_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long foreignClassId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "VN-Fremd-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "teacherB-" + unique;
            owner.persist();
            return foreign.id;
        });

        HttpResponse<String> response = post("/classes/" + foreignClassId + "/behavior-grid/cell", Map.of(
                "studentId", "1", "subjectId", "1", "value", "2"));
        assertEquals(404, response.statusCode());

        HttpResponse<String> pageResponse = get("/classes/" + foreignClassId + "/behavior-grid");
        assertEquals(404, pageResponse.statusCode());
    }

    @Test
    void studentAndSubjectFromDifferentOwnClasses_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classAId = createClass("VN-Mismatch-A-" + unique, "2025/26");
        Long studentInA = createStudent(classAId, "VN-Mismatch-Schueler-" + unique);

        Long classBId = createClass("VN-Mismatch-B-" + unique, "2025/26");
        Long subjectInB = createSubject(classBId, "VN-Mismatch-Fach-" + unique, gradeScale.id);

        // Both classA and classB are owned by the same (dev-user) teacher, but the student
        // belongs to A and the subject belongs to B - must still 404, not silently cross-link them.
        HttpResponse<String> response = post("/classes/" + classAId + "/behavior-grid/cell", Map.of(
                "studentId", String.valueOf(studentInA),
                "subjectId", String.valueOf(subjectInB),
                "value", "2"));
        assertEquals(404, response.statusCode());
    }

    @Test
    void deletingSubject_cascadesBehaviorGrades() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("VN-Cascade-" + unique, "2025/26");
        Long studentId = createStudent(classId, "VN-Cascade-Schueler-" + unique);
        Long subjectId = createSubject(classId, "VN-Cascade-Fach-" + unique, gradeScale.id);
        saveCellAndParse(classId, studentId, subjectId, "2");

        HttpResponse<String> deleteResponse = delete("/classes/" + classId + "/subjects/" + subjectId);
        assertEquals(200, deleteResponse.statusCode());

        Long remaining = QuarkusTransaction.requiringNew().call(
                () -> BehaviorGrade.count("subject.id", subjectId));
        assertEquals(0L, remaining);
    }

    @Test
    void gridPage_rendersSubjectColumnsAndStudentRows() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("VN-Seite-" + unique, "2025/26");
        String studentName = "VN-Seite-Schueler-" + unique;
        String subjectName = "VN-Seite-Fach-" + unique;
        createStudent(classId, studentName);
        createSubject(classId, subjectName, gradeScale.id);

        String html = get("/classes/" + classId + "/behavior-grid").body();
        assertTrue(html.contains(studentName));
        assertTrue(html.contains(subjectName));
        assertTrue(html.contains("data-max-row=\"0\""));
        assertTrue(html.contains("data-max-col=\"0\""));
    }

    private JsonNode saveCellAndParse(Long classId, Long studentId, Long subjectId, String value) throws Exception {
        HttpResponse<String> response = post("/classes/" + classId + "/behavior-grid/cell", Map.of(
                "studentId", String.valueOf(studentId),
                "subjectId", String.valueOf(subjectId),
                "value", value));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
    }

    private Long createSubject(Long classId, String name, Long gradeScaleId) throws Exception {
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
        return send("POST", path, form);
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).DELETE().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
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
