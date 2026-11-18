package de.notenfuchs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A student marked "ausgetreten": grades are kept and shown read-only below the tables, but the
 * student no longer counts in column averages or "offen" counts and can't get new grades.
 */
@QuarkusTest
class StudentLeftIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void leftStudent_keepsGradesReadOnlyAndLeavesTheAverages() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.annaId(), "2");
        saveCell(f, f.benId(), "6");

        HttpResponse<String> leave = http.post("/classes/" + f.classId() + "/students/" + f.benId() + "/leave", Map.of());
        assertEquals(200, leave.statusCode(), leave.body());
        String classPage = leave.body();

        assertTrue(classPage.contains("Ausgetreten"));
        assertNotNull(leftOn(f.benId()));
        String grid = http.get("/subjects/" + f.subjectId() + "/grid").body();
        int start = grid.indexOf("left-students");
        String leftSection = grid.substring(start, grid.indexOf("</tbody>", start));
        assertTrue(leftSection.contains("Ben-"), grid);
        assertFalse(leftSection.contains("<input"));
        // New grades are refused, and the column average now ignores Ben's 6.
        assertEquals(400, postCell(f, f.benId(), "5").statusCode());
        assertEquals("2", saveCell(f, f.annaId(), "2.0").get("assessmentRawAverage").asText());
        assertFalse(http.get("/subjects/" + f.subjectId() + "/grid/students/" + f.benId()).body().contains("Was braucht"));
    }

    @Test
    void leftStudent_isNotCountedAsMissing_andCanRejoin() throws Exception {
        Fixture f = fixture();
        saveCell(f, f.annaId(), "2");
        http.post("/classes/" + f.classId() + "/students/" + f.benId() + "/leave", Map.of());

        // Ben has no grade for the due Leistung, but he left: nothing is open in the Notenübersicht.
        String overview = http.get("/classes/" + f.classId() + "/overview").body();
        String gradeRow = overview.substring(overview.indexOf(">Noten offen<"), overview.indexOf(">Verhaltensnoten offen<"));
        assertFalse(gradeRow.contains("status-open"), gradeRow);
        assertFalse(http.get("/subjects/" + f.subjectId() + "/grid").body().contains("grade-cell-open"));

        http.post("/classes/" + f.classId() + "/students/" + f.benId() + "/rejoin", Map.of());
        assertNull(leftOn(f.benId()));
        assertEquals(200, postCell(f, f.benId(), "3").statusCode());
    }

    @Test
    void newSchoolYear_copiesOnlyStudentsStillInTheClass() throws Exception {
        Fixture f = fixture();
        http.post("/classes/" + f.classId() + "/students/" + f.benId() + "/leave", Map.of());
        String copyName = "Left-Kopie-" + System.nanoTime();

        http.post("/classes/" + f.classId() + "/duplicate", Map.of("name", copyName, "schoolYear", "2026/27"));

        Long copyId = SchoolClass.find("name", copyName).<SchoolClass>firstResult().id;
        assertEquals(1, Student.count("schoolClass.id", copyId));
    }

    private record Fixture(Long classId, Long subjectId, Long assessmentId, Long annaId, Long benId) {
    }

    private Fixture fixture() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String className = "Left-Klasse-" + unique;
        http.post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", className).<SchoolClass>firstResult().id;
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", "Left-Fach-" + unique, "gradeScaleId", gradeScaleId.toString(), "roundingMode", "COMMERCIAL"));
        Long subjectId = Subject.find("name", "Left-Fach-" + unique).<Subject>firstResult().id;
        http.post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        http.post("/classes/" + classId + "/students", Map.of("name", "Ben-" + unique));
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        http.post("/subjects/" + subjectId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA",
                "date", LocalDate.now().minusDays(1).toString()));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        Long annaId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        Long benId = Student.find("name", "Ben-" + unique).<Student>firstResult().id;
        return new Fixture(classId, subjectId, assessmentId, annaId, benId);
    }

    /** Read in a new transaction: the test's own session still holds the student as loaded by the fixture. */
    private static LocalDate leftOn(Long studentId) {
        return QuarkusTransaction.requiringNew().call(() -> Student.<Student>findById(studentId).leftOn);
    }

    private HttpResponse<String> postCell(Fixture f, Long studentId, String value) throws Exception {
        return http.post("/subjects/" + f.subjectId() + "/grid/cell", Map.of(
                "studentId", studentId.toString(), "assessmentId", f.assessmentId().toString(), "value", value));
    }

    private JsonNode saveCell(Fixture f, Long studentId, String value) throws Exception {
        HttpResponse<String> response = http.post("/subjects/" + f.subjectId() + "/grid/cell", Map.of(
                "studentId", studentId.toString(), "assessmentId", f.assessmentId().toString(), "value", value));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
