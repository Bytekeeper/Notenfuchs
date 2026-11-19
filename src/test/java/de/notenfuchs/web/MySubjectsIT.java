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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the "Meine Fächer" section on the home page. Uses school years that sort
 * after every other IT's ("9999/..."), since the section shows only the viewer's newest year and
 * the test DB is shared across ITs.
 */
@QuarkusTest
class MySubjectsIT {

    private static final String NEWEST_YEAR = "9999/00";
    private static final String OLDER_YEAR = "9998/99";

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void showsOnlySubjectsTheViewerTeaches_evenInAClassTheyAdminister() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Mine-Klasse-" + unique, NEWEST_YEAR);
        Long taughtId = createSubject(classId, "Mine-Fach-" + unique);
        seedSubjectTaughtByColleague(classId, "Mine-Fremdfach-" + unique, "colleague-" + unique);

        String html = http.get("/classes").body();

        assertTrue(html.contains("Meine Fächer"));
        assertTrue(html.contains("href=\"/subjects/" + taughtId + "/grid\""));
        assertTrue(html.contains("Mine-Klasse-" + unique + " – Mine-Fach-" + unique));
        assertFalse(html.contains("Mine-Fremdfach-" + unique));
    }

    @Test
    void showsOnlyTheNewestSchoolYear() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long oldClassId = createClass("Alt-Klasse-" + unique, OLDER_YEAR);
        createSubject(oldClassId, "Alt-Fach-" + unique);
        Long newClassId = createClass("Neu-Klasse-" + unique, NEWEST_YEAR);
        createSubject(newClassId, "Neu-Fach-" + unique);

        String html = http.get("/classes").body();

        assertTrue(html.contains("Neu-Fach-" + unique));
        assertFalse(html.contains("Alt-Fach-" + unique));
    }

    @Test
    void cardCountsMissingGradesOfLeistungenWhoseDateHasCome() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Offen-Klasse-" + unique, NEWEST_YEAR);
        Long subjectId = createSubject(classId, "Offen-Fach-" + unique);
        http.post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        http.post("/classes/" + classId + "/students", Map.of("name", "Ben-" + unique));
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", "Kat-" + unique, "weightPercent", "100"));
        Long categoryId = GradeCategory.find("name", "Kat-" + unique).<GradeCategory>firstResult().id;
        String yesterday = LocalDate.now().minusDays(1).toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "Vergangen-" + unique, "date", yesterday));
        // Not due yet, and undated: neither counts as missing.
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "Zukunft-" + unique, "date", tomorrow));
        http.post("/subjects/" + subjectId + "/grid/assessments",
                Map.of("categoryId", categoryId.toString(), "name", "Ohne-" + unique));
        Long pastId = Assessment.find("name", "Vergangen-" + unique).<Assessment>firstResult().id;
        Long annaId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        http.post("/subjects/" + subjectId + "/grid/cell",
                Map.of("studentId", annaId.toString(), "assessmentId", pastId.toString(), "value", "2"));

        String html = http.get("/classes").body();

        assertTrue(html.contains("1 Note offen"), html);
    }

    @Test
    void fachSwitcher_listsTheViewersFaecherOfTheSameYearOnly() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Wechsel-Klasse-" + unique, OLDER_YEAR);
        Long currentId = createSubject(classId, "Wechsel-Aktuell-" + unique);
        createSubject(classId, "Wechsel-Nachbar-" + unique);
        seedSubjectTaughtByColleague(classId, "Wechsel-Fremd-" + unique, "colleague-" + unique);
        Long otherYearClassId = createClass("Wechsel-Neu-" + unique, NEWEST_YEAR);
        createSubject(otherYearClassId, "Wechsel-AnderesJahr-" + unique);

        String html = http.get("/subjects/" + currentId + "/grid").body();

        assertTrue(html.contains("Fach wechseln"));
        assertTrue(html.contains("Wechsel-Nachbar-" + unique));
        assertFalse(html.contains("Wechsel-Fremd-" + unique));
        assertFalse(html.contains("Wechsel-AnderesJahr-" + unique));
    }

    private void seedSubjectTaughtByColleague(Long classId, String name, String colleagueSubject) {
        QuarkusTransaction.requiringNew().run(() -> {
            Subject subject = new Subject();
            subject.schoolClass = SchoolClass.findById(classId);
            subject.name = name;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = colleagueSubject;
            subjectTeacher.persist();
        });
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }
}
