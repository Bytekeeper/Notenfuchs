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
import java.net.URL;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

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

    private final TestHttp http = new TestHttp(() -> rootUrl);
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

        String html = http.get("/subjects/" + f.subjectId + "/grid").body();
        assertTrue(html.contains("value=\"e\""));
        assertTrue(html.contains("grade-cell-absent"));
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

        http.post("/subjects/" + f.subjectId + "/grid/assessments/" + f.assessmentId,
                Map.of("name", "KA", "factor", "1", "pointsBased", "true", "maxPoints", "40"));

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

        byte[] xlsx = http.getBytes("/subjects/" + f.subjectId + "/grid/export").body();

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
        http.post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", className).<SchoolClass>firstResult().id;
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", "Abs-Fach-" + unique, "gradeScaleId", gradeScaleId.toString(), "roundingMode", "COMMERCIAL"));
        Long subjectId = Subject.find("name", "Abs-Fach-" + unique).<Subject>firstResult().id;
        http.post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        http.post("/classes/" + classId + "/students", Map.of("name", "Ben-" + unique));
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        http.post("/subjects/" + subjectId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA",
                "date", LocalDate.now().minusDays(1).toString()));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        Long annaId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        Long benId = Student.find("name", "Ben-" + unique).<Student>firstResult().id;
        return new Fixture(subjectId, assessmentId, annaId, benId);
    }

    private JsonNode saveCell(Fixture f, Long studentId, String value) throws Exception {
        HttpResponse<String> response = http.post("/subjects/" + f.subjectId + "/grid/cell", Map.of(
                "studentId", studentId.toString(), "assessmentId", f.assessmentId.toString(), "value", value));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
