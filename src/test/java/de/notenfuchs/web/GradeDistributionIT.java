package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Notenspiegel tab: counts per whole grade for each Leistung and for the Zeugnisnoten. */
@QuarkusTest
class GradeDistributionIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void countsWholeGradesOfActiveStudents_andTheZeugnisnoteInsteadOfTheComputedGrade() throws Exception {
        String unique = Long.toString(System.nanoTime());
        http.post("/classes", Map.of("name", "Spiegel-Klasse-" + unique, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", "Spiegel-Klasse-" + unique).<SchoolClass>firstResult().id;
        Long scaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects", Map.of("name", "Spiegel-Fach-" + unique,
                "gradeScaleId", scaleId.toString(), "roundingMode", "COMMERCIAL"));
        Long subjectId = Subject.find("name", "Spiegel-Fach-" + unique).<Subject>firstResult().id;
        List<Long> students = new ArrayList<>();
        for (String name : List.of("Anna", "Ben", "Cem", "Dora")) {
            http.post("/classes/" + classId + "/students", Map.of("name", name + "-" + unique));
            students.add(Student.find("name", name + "-" + unique).<Student>firstResult().id);
        }
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        String date = LocalDate.now().minusDays(1).toString();
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "KA", "date", date));
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "Test", "date", date, "pointsBased", "true"));
        Long ka = Assessment.find("category.id = ?1 and name = 'KA'", categoryId).<Assessment>firstResult().id;
        Long test = Assessment.find("category.id = ?1 and name = 'Test'", categoryId).<Assessment>firstResult().id;

        saveCell(subjectId, students.get(0), ka, "2-");
        saveCell(subjectId, students.get(1), ka, "2");
        saveCell(subjectId, students.get(2), ka, "5");
        saveCell(subjectId, students.get(3), ka, "1");
        // The default Notenschlüssel gives the best grade from 60 points on.
        saveCell(subjectId, students.get(0), test, "60");
        http.post("/classes/" + classId + "/students/" + students.get(3) + "/leave", Map.of());
        http.post("/subjects/" + subjectId + "/grid/students/" + students.get(1) + "/report-grades", Map.of("year", "4"));

        String page = http.get("/subjects/" + subjectId + "/distribution").body();

        // Leistung, Datum, 1..6, Ø, Noten. Dora left, so her 1 doesn't count.
        assertEquals(List.of("0", "2", "0", "0", "1", "0", "3.1", "3"), counts(page, "KA"));
        assertEquals(List.of("1", "0", "0", "0", "0", "0", "1", "1"), counts(page, "Test"));
        // Computed: Anna 2 (1.65), Ben 2 but set to 4 by hand, Cem 5.
        assertEquals(List.of("0", "1", "0", "1", "1", "0"), counts(page, "Jahr").subList(0, 6));
    }

    @Test
    void teacherWhoDoesntTeachTheFach_isNotFound() throws Exception {
        String className = "Fremde-Klasse-" + System.nanoTime();
        http.post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", className).<SchoolClass>firstResult().id;
        // Class admin, but no teacher of this Fach: no admin override inside a Fach.
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            Subject subject = new Subject();
            subject.schoolClass = SchoolClass.findById(classId);
            subject.name = "Fremdes-Fach";
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher teacher = new SubjectTeacher();
            teacher.subject = subject;
            teacher.teacherSubject = "teacherB-" + System.nanoTime();
            teacher.persist();
            return subject.id;
        });

        assertEquals(404, http.get("/subjects/" + subjectId + "/distribution").statusCode());
    }

    private void saveCell(Long subjectId, Long studentId, Long assessmentId, String value) throws Exception {
        assertEquals(200, http.post("/subjects/" + subjectId + "/grid/cell", Map.of("studentId", studentId.toString(),
                "assessmentId", assessmentId.toString(), "value", value)).statusCode());
    }

    /** The count, Ø and Noten cells of the row whose first cell starts with {@code name}. */
    private static List<String> counts(String page, String name) {
        Matcher row = Pattern.compile("<tr>\\s*<td>" + Pattern.quote(name) + "[ <].*?</tr>", Pattern.DOTALL).matcher(page);
        if (!row.find()) {
            throw new AssertionError("No row " + name + " in " + page);
        }
        Matcher cell = Pattern.compile("<td class=\"count[^\"]*\">([^<]*)</td>").matcher(row.group());
        List<String> cells = new ArrayList<>();
        while (cell.find()) {
            cells.add(cell.group(1));
        }
        return cells;
    }
}
