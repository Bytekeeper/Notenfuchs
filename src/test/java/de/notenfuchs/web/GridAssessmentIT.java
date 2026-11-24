package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void addRedirectsToTheGridFocusedOnTheNewColumn() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse-" + unique);
        Long subjectId = createSubject(classId, "GA-Fach-" + unique);
        Long categoryId = createCategory(subjectId, "GA-Kat-" + unique);

        HttpResponse<String> response = http.post("/subjects/" + subjectId + "/grid/assessments",
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
        http.post("/subjects/" + subjectB + "/grid/assessments", Map.of("categoryId", categoryB.toString(), "name", "KA-B"));
        Long assessmentB = Assessment.find("category.id", categoryB).<Assessment>firstResult().id;

        assertEquals(404, http.post("/subjects/" + subjectA + "/grid/assessments",
                Map.of("categoryId", categoryB.toString(), "name", "KA-falsch")).statusCode());
        assertEquals(404, http.post("/subjects/" + subjectA + "/grid/assessments/" + assessmentB,
                Map.of("name", "umbenannt", "factor", "1")).statusCode());
        assertEquals(404, http.post("/subjects/" + subjectA + "/grid/assessments/" + assessmentB + "/delete", Map.of())
                .statusCode());
        assertEquals("KA-B", Assessment.<Assessment>findById(assessmentB).name);
    }

    @Test
    void pointsLeistung_takesItsStepsFromTheFachKey_andKeepsThemWhenTheKeyChanges() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse3-" + unique);
        Long subjectId = createSubject(classId, "GA-Fach3-" + unique);
        Long categoryId = createCategory(subjectId, "GA-Kat3-" + unique);
        String create = "/subjects/" + subjectId + "/grid/assessments";

        assertEquals(400, http.post(create, Map.of("categoryId", categoryId.toString(), "name", "Ohne",
                "pointsBased", "true")).statusCode());
        assertEquals(0, Assessment.count("category.id", categoryId));

        // The default key: 87,5 / 75 / 62,5 / 50 / 25 % of 37 points, rounded down to half points.
        http.post(create, Map.of("categoryId", categoryId.toString(), "name", "KA1", "pointsBased", "true", "maxPoints", "37"));
        assertEquals("1@32, 2@27.5, 3@23, 4@18.5, 5@9", steps(categoryId, "KA1"));

        // A teacher's sliding key: 100 % → 1,0, 50 % → 3,5, 20 % → 6. Out of order is refused.
        String key = "/subjects/" + subjectId + "/points-key";
        assertEquals(400, http.post(key, "grade=1&percent=50&grade=2&percent=60").statusCode());
        http.post(key, "grade=1&percent=100&grade=3%2C5&percent=50&grade=6&percent=20&sliding=true");
        http.post(create, Map.of("categoryId", categoryId.toString(), "name", "KA2", "pointsBased", "true", "maxPoints", "40"));
        assertEquals("1@40, 3.5@20, 6@8", steps(categoryId, "KA2"));
        assertEquals("1@32, 2@27.5, 3@23, 4@18.5, 5@9", steps(categoryId, "KA1"));

        // 30 of 40 points lies between 1 and 3,5: 2,25, in the student's favor 2,2.
        String studentName = "GA-Schueler-" + unique;
        http.post("/classes/" + classId + "/students", Map.of("name", studentName));
        Long studentId = Student.find("name", studentName).<Student>firstResult().id;
        Long ka2 = Assessment.find("category.id = ?1 and name = 'KA2'", categoryId).<Assessment>firstResult().id;
        String saved = http.post("/subjects/" + subjectId + "/grid/cell", Map.of("studentId", studentId.toString(),
                "assessmentId", ka2.toString(), "value", "30")).body();
        assertTrue(saved.contains("\"derivedGradeDisplay\":\"2,2\""), saved);
    }

    @Test
    void aFachCanShowTendenciesAsNumbers() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse5-" + unique);
        Long subjectId = createSubject(classId, "GA-Fach5-" + unique);
        Long categoryId = createCategory(subjectId, "GA-Kat5-" + unique);
        http.post("/subjects/" + subjectId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA"));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        http.post("/classes/" + classId + "/students", Map.of("name", "GA-Tendenz-" + unique));
        Long studentId = Student.find("name", "GA-Tendenz-" + unique).<Student>firstResult().id;
        Map<String, String> cell = Map.of("studentId", studentId.toString(), "assessmentId", assessmentId.toString(), "value", "2-");

        assertTrue(http.post("/subjects/" + subjectId + "/grid/cell", cell).body().contains("\"displayValue\":\"2-\""));
        http.patch("/classes/" + classId + "/subjects/" + subjectId + "/rename", Map.of("tendencySigns", "false"));
        assertTrue(http.post("/subjects/" + subjectId + "/grid/cell", cell).body().contains("\"displayValue\":\"2,3\""));
        assertTrue(http.get("/subjects/" + subjectId + "/grid").body().contains("value=\"2,3\""));
    }

    @Test
    void notenschluessel_isReplacedWhole_andAnInvalidStepChangesNothing() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("GA-Klasse4-" + unique);
        Long subjectId = createSubject(classId, "GA-Fach4-" + unique);
        Long categoryId = createCategory(subjectId, "GA-Kat4-" + unique);
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "Klausur", "pointsBased", "true", "maxPoints", "40"));
        Long assessmentId = Assessment.find("category.id", categoryId).<Assessment>firstResult().id;
        String url = "/subjects/" + subjectId + "/grid/assessments/" + assessmentId;

        for (String badSteps : new String[] {
                "&bandPoints=35&bandGrades=1&bandPoints=10&bandGrades=7",   // not on the scale
                "&bandPoints=35&bandGrades=1&bandPoints=36&bandGrades=2",   // better grade, fewer points
                "&bandPoints=35.3&bandGrades=1"}) {                         // not a half point
            HttpResponse<String> invalid = http.post(url, "name=Umbenannt&factor=1&pointsBased=true&maxPoints=40" + badSteps);
            assertEquals(400, invalid.statusCode(), badSteps);
        }
        assertEquals("Klausur", Assessment.<Assessment>findById(assessmentId).name);
        assertEquals(5, PointsGradeBand.count("assessment.id", assessmentId));

        http.post(url, "name=Klausur&factor=1&pointsBased=true&maxPoints=40"
                + "&bandPoints=36&bandGrades=1&bandPoints=33&bandGrades=2%2B&bandPoints=20&bandGrades=4");
        assertEquals("1@36, 1.7@33, 4@20", steps(categoryId, "Klausur"));
    }

    private static String steps(Long categoryId, String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Assessment assessment = Assessment.find("category.id = ?1 and name = ?2", categoryId, name).firstResult();
            return PointsGradeBand.<PointsGradeBand>list("assessment.id = ?1 order by points desc", assessment.id).stream()
                    .map(b -> b.gradeValue.stripTrailingZeros().toPlainString() + "@" + b.points.stripTrailingZeros().toPlainString())
                    .collect(java.util.stream.Collectors.joining(", "));
        });
    }

    private Long createClass(String name) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", "2025/26"));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }

    private Long createCategory(Long subjectId, String name) throws Exception {
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", name, "weightPercent", "100"));
        return GradeCategory.find("name", name).<GradeCategory>firstResult().id;
    }
}
