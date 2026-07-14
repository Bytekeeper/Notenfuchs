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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the Halbjahr split view ({@link GradeGridResource}'s
 * {@code halfYearCutoff}-driven rendering): per-half averages match calling
 * {@link de.notenfuchs.service.GradeService} on that half's date-subset alone, the boundary date
 * belongs to the first half, an undated Assessment counts only into "Jahr", and a null cutoff
 * leaves the classic single view untouched. A Failsafe IT (./mvnw verify) - needs a real Postgres
 * (Testcontainers Dev Services), same reasoning as {@link ClassDuplicationIT}.
 *
 * <p>Drives the real endpoints over plain HTTP rather than calling {@link GradeGridResource} or
 * {@link ClassUiResource} in-process (see {@link ClassDuplicationIT}'s javadoc for why), and
 * parses the {@code /grid/cell} JSON response with Jackson (already a transitive dependency via
 * {@code quarkus-rest-jackson}) rather than scraping the rendered grid HTML wherever a value can
 * be asserted through that live-update payload instead.
 */
@QuarkusTest
class GradeGridHalfYearIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    private static final LocalDate CUTOFF = LocalDate.of(2026, 1, 31);

    @Test
    void halfAverages_matchDateSubset_boundaryGoesToFirstHalf_undatedOnlyInJahr() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("HJ-Klasse-" + unique, "2025/26");
        setHalfYearCutoff(classId, CUTOFF);
        Long studentId = createStudent(classId, "HJ-Schueler-" + unique);
        Long subjectId = createSubject(classId, "HJ-Fach-" + unique, gradeScale.id);
        Long categoryAId = createCategory(subjectId, "HJ-KatA-" + unique, "50");
        Long categoryBId = createCategory(subjectId, "HJ-KatB-" + unique, "50");

        // X: dated exactly ON the cutoff -> belongs to H1, not H2.
        Long assessmentX = createAssessment(subjectId, categoryAId, "HJ-X-" + unique, CUTOFF);
        // Y: dated the day after the cutoff -> H2.
        Long assessmentY = createAssessment(subjectId, categoryBId, "HJ-Y-" + unique, CUTOFF.plusDays(1));
        // Z: undated -> counts only into Jahr, never a half.
        Long assessmentZ = createAssessment(subjectId, categoryAId, "HJ-Z-" + unique, null);

        // Grade X only: Jahr = H1 = 2 (categoryB still empty and excluded); H2 empty (no grades yet).
        JsonNode afterX = saveGradeAndParse(subjectId, studentId, assessmentX, "2");
        assertEquals("2", afterX.get("rawAverage").asText());
        assertEquals("2", afterX.get("h1RawAverage").asText());
        assertFalse(afterX.hasNonNull("h2RawAverage"));
        assertFalse(afterX.hasNonNull("h2FinalGrade"));

        // Grade Y: categoryB now has its H2 grade. Jahr combines A(2)+B(4) 50/50 -> 3.
        // H1 must stay exactly 2 (Y is not part of H1's assessment subset at all).
        JsonNode afterY = saveGradeAndParse(subjectId, studentId, assessmentY, "4");
        assertEquals("3", afterY.get("rawAverage").asText());
        assertEquals("2", afterY.get("h1RawAverage").asText());
        assertEquals("4", afterY.get("h2RawAverage").asText());

        // Grade Z (undated, worst grade 6): only Jahr changes (categoryA average becomes (2+6)/2=4,
        // so Jahr = (4*50+4*50)/100 = 4). H1 and H2 must be UNCHANGED - Z leaks into neither half.
        JsonNode afterZ = saveGradeAndParse(subjectId, studentId, assessmentZ, "6");
        assertEquals("4", afterZ.get("rawAverage").asText());
        assertEquals("2", afterZ.get("h1RawAverage").asText());
        assertEquals("4", afterZ.get("h2RawAverage").asText());
    }

    @Test
    void nullCutoff_cellSaveResponse_hasNoHalfYearAverages_andClassicTemplateRenders() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("HJ-Klassisch-" + unique, "2025/26");
        Long studentId = createStudent(classId, "HJ-Klassisch-Schueler-" + unique);
        Long subjectId = createSubject(classId, "HJ-Klassisch-Fach-" + unique, gradeScale.id);
        Long categoryId = createCategory(subjectId, "HJ-Klassisch-Kat-" + unique, "100");
        Long assessmentId = createAssessment(subjectId, categoryId, "HJ-Klassisch-Leistung-" + unique, null);

        JsonNode response = saveGradeAndParse(subjectId, studentId, assessmentId, "2");
        assertEquals("2", response.get("rawAverage").asText());
        assertFalse(response.hasNonNull("h1RawAverage"));
        assertFalse(response.hasNonNull("h1FinalGrade"));
        assertFalse(response.hasNonNull("h2RawAverage"));
        assertFalse(response.hasNonNull("h2FinalGrade"));

        String gridHtml = get("/subjects/" + subjectId + "/grid").body();
        assertFalse(gridHtml.contains("region-header"), "classic (null cutoff) grid must not render Halbjahr regions");
    }

    @Test
    void gridPage_withCutoff_showsRegions_undatedBlockOnlyWhenAssessmentsExist() throws Exception {
        String unique = Long.toString(System.nanoTime());
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        // Subject A: has an undated Assessment -> "Ohne Datum" block must render.
        Long classAId = createClass("HJ-Regionen-A-" + unique, "2025/26");
        setHalfYearCutoff(classAId, CUTOFF);
        createStudent(classAId, "HJ-Regionen-A-Schueler-" + unique);
        Long subjectAId = createSubject(classAId, "HJ-Regionen-A-Fach-" + unique, gradeScale.id);
        Long categoryAId = createCategory(subjectAId, "HJ-Regionen-A-Kat-" + unique, "100");
        createAssessment(subjectAId, categoryAId, "HJ-Regionen-A-Undatiert-" + unique, null);
        createAssessment(subjectAId, categoryAId, "HJ-Regionen-A-H1-" + unique, CUTOFF.minusDays(1));

        String htmlWithUndated = get("/subjects/" + subjectAId + "/grid").body();
        assertTrue(htmlWithUndated.contains("Ohne Datum"), "undated Assessment exists -> block must render");
        assertTrue(htmlWithUndated.contains("kein Datum"), "undated block must carry the warning hint");
        assertTrue(htmlWithUndated.contains("1. Halbjahr"));
        assertTrue(htmlWithUndated.contains("2. Halbjahr"));
        assertTrue(htmlWithUndated.contains(">Jahr<"));

        // Subject B: every Assessment is dated -> "Ohne Datum" block must be absent entirely.
        Long classBId = createClass("HJ-Regionen-B-" + unique, "2025/26");
        setHalfYearCutoff(classBId, CUTOFF);
        createStudent(classBId, "HJ-Regionen-B-Schueler-" + unique);
        Long subjectBId = createSubject(classBId, "HJ-Regionen-B-Fach-" + unique, gradeScale.id);
        Long categoryBId = createCategory(subjectBId, "HJ-Regionen-B-Kat-" + unique, "100");
        createAssessment(subjectBId, categoryBId, "HJ-Regionen-B-H1-" + unique, CUTOFF.minusDays(1));
        createAssessment(subjectBId, categoryBId, "HJ-Regionen-B-H2-" + unique, CUTOFF.plusDays(1));

        String htmlWithoutUndated = get("/subjects/" + subjectBId + "/grid").body();
        assertFalse(htmlWithoutUndated.contains("Ohne Datum"), "no undated Assessment -> block must not render");
        assertTrue(htmlWithoutUndated.contains("1. Halbjahr"));
        assertTrue(htmlWithoutUndated.contains("2. Halbjahr"));
    }

    @Test
    void xlsxExport_withCutoff_mirrorsHalfYearStructure() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String studentName = "HJ-Excel-Schueler-" + unique;
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass("HJ-Excel-" + unique, "2025/26");
        setHalfYearCutoff(classId, CUTOFF);
        Long studentId = createStudent(classId, studentName);
        Long subjectId = createSubject(classId, "HJ-Excel-Fach-" + unique, gradeScale.id);
        Long categoryId = createCategory(subjectId, "HJ-Excel-Kat-" + unique, "100");
        Long assessmentX = createAssessment(subjectId, categoryId, "HJ-Excel-X-" + unique, CUTOFF.minusDays(1));
        Long assessmentY = createAssessment(subjectId, categoryId, "HJ-Excel-Y-" + unique, CUTOFF.plusDays(1));
        saveGradeAndParse(subjectId, studentId, assessmentX, "2");
        saveGradeAndParse(subjectId, studentId, assessmentY, "4");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/subjects/" + subjectId + "/grid/export"))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());

        try (Workbook workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(response.body()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Set<String> regionLabels = new HashSet<>();
            for (Cell cell : sheet.getRow(0)) {
                if (cell.getCellType() == CellType.STRING) {
                    regionLabels.add(cell.getStringCellValue());
                }
            }
            assertTrue(regionLabels.contains("1. Halbjahr"));
            assertTrue(regionLabels.contains("2. Halbjahr"));
            assertTrue(regionLabels.contains("Jahr"));

            Row studentRow = findRowContaining(sheet, studentName);
            Set<Double> numericValues = new HashSet<>();
            for (Cell cell : studentRow) {
                if (cell.getCellType() == CellType.NUMERIC) {
                    numericValues.add(cell.getNumericCellValue());
                }
            }
            // Entered grades (2, 4) plus H1=2/H2=4/Jahr=3 all appear somewhere in the row.
            assertTrue(numericValues.contains(2.0));
            assertTrue(numericValues.contains(4.0));
            assertTrue(numericValues.contains(3.0));
        }
    }

    @Test
    void halfYearCutoffUpdate_onForeignClass_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long foreignClassId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "HJ-Fremd-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.ownerSubject = "teacherB-" + unique;
            foreign.persist();
            return foreign.id;
        });

        HttpResponse<String> response = patch("/classes/" + foreignClassId + "/half-year-cutoff",
                Map.of("halfYearCutoff", CUTOFF.toString()));
        assertEquals(404, response.statusCode());

        SchoolClass unchanged = QuarkusTransaction.requiringNew().call(() -> SchoolClass.findById(foreignClassId));
        assertNull(unchanged.halfYearCutoff);
    }

    private Row findRowContaining(Sheet sheet, String value) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && value.equals(first.getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("no row found with first cell = " + value);
    }

    private JsonNode saveGradeAndParse(Long subjectId, Long studentId, Long assessmentId, String value) throws Exception {
        HttpResponse<String> response = post("/subjects/" + subjectId + "/grid/cell", Map.of(
                "studentId", String.valueOf(studentId),
                "assessmentId", String.valueOf(assessmentId),
                "value", value));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void setHalfYearCutoff(Long classId, LocalDate cutoff) throws Exception {
        HttpResponse<String> response = patch("/classes/" + classId + "/half-year-cutoff",
                Map.of("halfYearCutoff", cutoff.toString()));
        assertEquals(200, response.statusCode(), response.body());
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

    private Long createCategory(Long subjectId, String name, String weightPercent) throws Exception {
        post("/subjects/" + subjectId + "/categories", Map.of("name", name, "weightPercent", weightPercent));
        return GradeCategory.find("name", name).<GradeCategory>firstResult().id;
    }

    private Long createAssessment(Long subjectId, Long categoryId, String name, LocalDate date) throws Exception {
        Map<String, String> form = date != null
                ? Map.of("name", name, "factor", "1", "date", date.toString())
                : Map.of("name", name, "factor", "1");
        post("/subjects/" + subjectId + "/categories/" + categoryId + "/assessments", form);
        return Assessment.find("name", name).<Assessment>firstResult().id;
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

    private HttpResponse<String> patch(String path, Map<String, String> form) throws Exception {
        return send("PATCH", path, form);
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
