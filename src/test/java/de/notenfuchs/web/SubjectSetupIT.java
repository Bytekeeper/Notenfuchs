package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP-level coverage for the two ways a new Fach gets categories without typing them:
 * copying another Fach's setup on creation, and the one-click starter categories.
 */
@QuarkusTest
class SubjectSetupIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void newFach_copiesCategoriesButNotLeistungen() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Setup-Klasse-" + unique);
        Long sourceId = createSubject(classId, "Setup-Quelle-" + unique, null);
        Long categoryId = createCategory(sourceId, "Klausuren", "60");
        createCategory(sourceId, "Mitarbeit", "40");
        http.post("/subjects/" + sourceId + "/grid/assessments", Map.of("categoryId", categoryId.toString(), "name", "KA1"));

        Long copyId = createSubject(classId, "Setup-Kopie-" + unique, sourceId);

        List<GradeCategory> copied = GradeCategory.list("subject.id = ?1 order by id", copyId);
        assertEquals(List.of("Klausuren", "Mitarbeit"), copied.stream().map(c -> c.name).toList());
        assertEquals(0, new BigDecimal("60").compareTo(copied.get(0).weightPercent));
        assertEquals(0, Assessment.count("category.subject.id", copyId));
    }

    @Test
    void newFach_cannotCopyFromAFachTheViewerDoesNotTeach() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Setup-Klasse2-" + unique);
        Long foreignId = seedSubjectTaughtByColleague(classId, "Setup-Fremd-" + unique);

        HttpResponse<String> response = http.post("/classes/" + classId + "/subjects", subjectForm("Setup-Neu-" + unique, foreignId));

        assertEquals(404, response.statusCode());
        assertEquals(0, Subject.count("name", "Setup-Neu-" + unique));
    }

    @Test
    void starterCategories_createTwoHalvesOnlyOnce() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Setup-Klasse3-" + unique);
        Long subjectId = createSubject(classId, "Setup-Start-" + unique, null);

        HttpResponse<String> first = http.post("/subjects/" + subjectId + "/grid/starter-categories", Map.of());
        http.post("/subjects/" + subjectId + "/grid/starter-categories", Map.of("returnTo", "setup"));

        assertEquals("/subjects/" + subjectId + "/grid", first.headers().firstValue("Location").orElseThrow());
        List<GradeCategory> categories = GradeCategory.list("subject.id = ?1 order by id", subjectId);
        assertEquals(List.of("Schriftlich", "Mündlich"), categories.stream().map(c -> c.name).toList());
    }

    private Long seedSubjectTaughtByColleague(Long classId, String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Subject subject = new Subject();
            subject.schoolClass = SchoolClass.findById(classId);
            subject.name = name;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "colleague-" + name;
            subjectTeacher.persist();
            return subject.id;
        });
    }

    private Long createClass(String name) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", "2025/26"));
        return SchoolClass.find("name", name).<SchoolClass>firstResult().id;
    }

    private Long createSubject(Long classId, String name, Long copyCategoriesFrom) throws Exception {
        http.post("/classes/" + classId + "/subjects", subjectForm(name, copyCategoriesFrom));
        return Subject.find("name", name).<Subject>firstResult().id;
    }

    private static Map<String, String> subjectForm(String name, Long copyCategoriesFrom) {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        return Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL",
                "copyCategoriesFrom", copyCategoriesFrom == null ? "" : copyCategoriesFrom.toString());
    }

    private Long createCategory(Long subjectId, String name, String weight) throws Exception {
        http.post("/subjects/" + subjectId + "/categories", Map.of("name", name, "weightPercent", weight));
        return GradeCategory.find("subject.id = ?1 and name = ?2", subjectId, name).<GradeCategory>firstResult().id;
    }
}
