package de.notenfuchs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.ReportGrade;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grades typed with a tendency ("2-") and the Zeugnisnote a teacher sets by hand on the student page. */
@QuarkusTest
class ReportGradeIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void tendencyEntry_countsAsTheStepAndShowsAsTyped() throws Exception {
        Fixture f = fixture();

        JsonNode saved = saveCell(f, "2-");

        assertEquals("2-", saved.get("displayValue").asText());
        assertEquals("2,3", saved.get("rawAverage").asText());
        assertEquals(0, new BigDecimal("2.3").compareTo(storedGrade(f)));
        assertTrue(http.get("/subjects/" + f.subjectId() + "/grid").body().contains("value=\"2-\""));
        // 1+ would be 0.7, outside the scale.
        assertEquals(400, cellResponse(f, "1+").statusCode());
    }

    @Test
    void reportGrade_countsInsteadOfTheComputedGrade() throws Exception {
        Fixture f = fixture();
        saveCell(f, "5");

        assertEquals(303, setReportGrades(f, Map.of("year", "4")).statusCode());

        String overview = http.get("/classes/" + f.classId() + "/overview").body();
        assertTrue(overview.contains(">4</span>"), overview);
        // The computed 5 would put the student at risk; the Zeugnisnote 4 doesn't.
        assertTrue(overview.contains("class=\"private average-cell has-report-grade\""), overview);
        assertTrue(http.get("/subjects/" + f.subjectId() + "/grid").body().contains("has-report-grade"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(
                http.getBytes("/classes/" + f.classId() + "/export").body()))) {
            Sheet overviewSheet = workbook.getSheet(ClassExportResource.OVERVIEW_SHEET);
            assertEquals(4, overviewSheet.getRow(1).getCell(1).getNumericCellValue());
            Sheet fachSheet = workbook.getSheetAt(1);
            Row header = fachSheet.getRow(0);
            int reportCol = header.getLastCellNum() - 1;
            assertEquals("Zeugnisnote", header.getCell(reportCol).getStringCellValue());
            assertEquals(5, fachSheet.getRow(2).getCell(reportCol - 1).getNumericCellValue());
            assertEquals(4, fachSheet.getRow(2).getCell(reportCol).getNumericCellValue());
        }
    }

    @Test
    void reportGrade_rejectsBadInputAndClearsWhenEmpty() throws Exception {
        Fixture f = fixture();

        assertTrue(location(setReportGrades(f, Map.of("year", "2-"))).endsWith("reportGradeError=whole#zeugnisnote"));
        assertTrue(location(setReportGrades(f, Map.of("year", "7"))).endsWith("reportGradeError=range#zeugnisnote"));
        assertTrue(location(setReportGrades(f, Map.of("year", "gut"))).endsWith("reportGradeError=invalid#zeugnisnote"));
        assertNull(reportGrade(f, ReportGrade.Period.YEAR));

        setReportGrades(f, Map.of("year", "3"));
        assertEquals(0, new BigDecimal("3").compareTo(reportGrade(f, ReportGrade.Period.YEAR)));
        setReportGrades(f, Map.of("year", ""));
        assertNull(reportGrade(f, ReportGrade.Period.YEAR));
    }

    @Test
    void firstHalf_takesATendencyButOnlyWithAHalfYearCutoff() throws Exception {
        Fixture f = fixture();

        setReportGrades(f, Map.of("firstHalf", "2-", "year", ""));
        assertNull(reportGrade(f, ReportGrade.Period.FIRST_HALF));

        http.patch("/classes/" + f.classId() + "/half-year-cutoff", Map.of("halfYearCutoff", "2026-01-31"));
        setReportGrades(f, Map.of("firstHalf", "2-", "year", ""));

        assertEquals(0, new BigDecimal("2.3").compareTo(reportGrade(f, ReportGrade.Period.FIRST_HALF)));
        String page = http.get("/subjects/" + f.subjectId() + "/grid/students/" + f.studentId()).body();
        assertTrue(page.contains("value=\"2-\""), page);
    }

    @Test
    void reportGrade_studentWhoLeft_isRefused() throws Exception {
        Fixture f = fixture();
        http.post("/classes/" + f.classId() + "/students/" + f.studentId() + "/leave", Map.of());

        assertEquals(400, setReportGrades(f, Map.of("year", "3")).statusCode());
    }

    @Test
    void reportGrade_classAdminWhoDoesntTeachTheFach_isNotFound() throws Exception {
        Fixture f = fixture();
        Long foreignSubjectId = QuarkusTransaction.requiringNew().call(() -> {
            Subject subject = new Subject();
            subject.schoolClass = SchoolClass.findById(f.classId());
            subject.name = "Fremdes-Fach-" + System.nanoTime();
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher teacher = new SubjectTeacher();
            teacher.subject = subject;
            teacher.teacherSubject = "teacherB-" + System.nanoTime();
            teacher.persist();
            return subject.id;
        });

        HttpResponse<String> response = http.post("/subjects/" + foreignSubjectId + "/grid/students/"
                + f.studentId() + "/report-grades", Map.of("year", "3"));

        assertEquals(404, response.statusCode());
    }

    private record Fixture(Long classId, Long subjectId, Long assessmentId, Long studentId) {
    }

    private Fixture fixture() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String className = "Zeugnis-Klasse-" + unique;
        http.post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", className).<SchoolClass>firstResult().id;
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", "Zeugnis-Fach-" + unique, "gradeScaleId", gradeScaleId.toString(), "roundingMode", "COMMERCIAL"));
        Long subjectId = Subject.find("name", "Zeugnis-Fach-" + unique).<Subject>firstResult().id;
        http.post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        http.post("/subjects/" + subjectId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA",
                "date", LocalDate.now().minusDays(1).toString()));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        Long studentId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        return new Fixture(classId, subjectId, assessmentId, studentId);
    }

    private HttpResponse<String> cellResponse(Fixture f, String value) throws Exception {
        return http.post("/subjects/" + f.subjectId() + "/grid/cell", Map.of(
                "studentId", f.studentId().toString(), "assessmentId", f.assessmentId().toString(), "value", value));
    }

    private JsonNode saveCell(Fixture f, String value) throws Exception {
        HttpResponse<String> response = cellResponse(f, value);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> setReportGrades(Fixture f, Map<String, String> form) throws Exception {
        return http.post("/subjects/" + f.subjectId() + "/grid/students/" + f.studentId() + "/report-grades", form);
    }

    private static String location(HttpResponse<String> response) {
        assertEquals(303, response.statusCode(), response.body());
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static BigDecimal storedGrade(Fixture f) {
        return QuarkusTransaction.requiringNew().call(() -> Grade.<Grade>find(
                "assessment.id = ?1 and student.id = ?2", f.assessmentId(), f.studentId()).firstResult().value);
    }

    private static BigDecimal reportGrade(Fixture f, ReportGrade.Period period) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportGrade grade = ReportGrade.find("subject.id = ?1 and student.id = ?2 and period = ?3",
                    f.subjectId(), f.studentId(), period).firstResult();
            return grade != null ? grade.value : null;
        });
    }
}
