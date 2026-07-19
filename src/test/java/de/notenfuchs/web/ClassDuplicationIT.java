package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for the "copy class into a new school year" action
 * ({@link ClassUiResource#duplicate}): asserts what actually gets cloned (Subjects +
 * GradeCategories + Students) versus what deliberately doesn't (Assessments/Grades - fresh
 * start), that both classes stay independently editable afterwards, and that ownership is
 * enforced. A Failsafe IT (./mvnw verify) since it needs a real Postgres (Testcontainers Dev
 * Services), same reasoning as {@link de.notenfuchs.security.OwnershipGuardIT}.
 *
 * <p>Drives the real endpoint over plain HTTP (no RestAssured in this project's dependencies,
 * and no browser needed here - {@code de.notenfuchs.e2e.ClassDuplicationE2EIT} covers the
 * actual UI flow) rather than calling {@link ClassUiResource} in-process, since the %test
 * profile's ownership subject ("dev-user", see {@link de.notenfuchs.security.CurrentUser}) is
 * only resolved from a real request. Fixture entities are created through the app's own form
 * endpoints and then looked up by their (unique, nanoTime-suffixed) names directly via
 * Panache, rather than parsing HTML/redirect responses.
 */
@QuarkusTest
class ClassDuplicationIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void duplicateCopiesSubjectsCategoriesAndStudents_butNotAssessmentsOrGrades() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String className = "Dup-Klasse-" + unique;
        String subjectName = "Dup-Fach-" + unique;
        String categoryName = "Dup-Kategorie-" + unique;
        String assessmentName = "Dup-Leistung-" + unique;
        String studentName = "Dup-Schueler-" + unique;
        String newClassName = "Dup-Klasse-Neu-" + unique;
        String newSchoolYear = "2026/27";

        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();

        Long classId = createClass(className, "2025/26");
        Long subjectId = createSubject(classId, subjectName, gradeScale.id);
        Long categoryId = createCategory(subjectId, categoryName);
        Long assessmentId = createAssessment(subjectId, categoryId, assessmentName);
        Long studentId = createStudent(classId, studentName);
        saveGrade(subjectId, studentId, assessmentId, "2");

        HttpResponse<String> response = post("/classes/" + classId + "/duplicate",
                Map.of("name", newClassName, "schoolYear", newSchoolYear));
        assertEquals(303, response.statusCode());

        SchoolClass newClass = SchoolClass.find("name", newClassName).firstResult();
        assertEquals(newSchoolYear, newClass.schoolYear);

        SchoolClass newClassWithPredecessor = SchoolClass
                .find("from SchoolClass c left join fetch c.predecessorClass where c.id = ?1", newClass.id)
                .firstResult();
        assertEquals(classId, newClassWithPredecessor.predecessorClass.id);

        List<Subject> newSubjects = Subject.list("schoolClass.id", newClass.id);
        assertEquals(1, newSubjects.size());
        Subject newSubject = newSubjects.get(0);
        assertEquals(subjectName, newSubject.name);

        List<GradeCategory> newCategories = GradeCategory.list("subject.id", newSubject.id);
        assertEquals(1, newCategories.size());
        assertEquals(categoryName, newCategories.get(0).name);

        List<Student> newStudents = Student.list("schoolClass.id", newClass.id);
        assertEquals(1, newStudents.size());
        assertEquals(studentName, newStudents.get(0).name);

        // Fresh start: no Assessments/Grades copied into the new class.
        assertTrue(Assessment.list("category.id", newCategories.get(0).id).isEmpty());
        assertEquals(0L, Grade.count("student.id", newStudents.get(0).id));

        // Source class is untouched.
        assertEquals(1, Assessment.list("category.id", categoryId).size());
        assertEquals(1L, Grade.count("student.id", studentId));

        // Both classes stay independently editable afterwards.
        Long extraOldStudentId = createStudent(classId, "Dup-Alt-Extra-" + unique);
        Long extraNewStudentId = createStudent(newClass.id, "Dup-Neu-Extra-" + unique);
        assertEquals(2, Student.list("schoolClass.id", classId).size());
        assertEquals(2, Student.list("schoolClass.id", newClass.id).size());
        Student extraOldStudent = Student.findById(extraOldStudentId);
        Student extraNewStudent = Student.findById(extraNewStudentId);
        assertEquals("Dup-Alt-Extra-" + unique, extraOldStudent.name);
        assertEquals("Dup-Neu-Extra-" + unique, extraNewStudent.name);
    }

    @Test
    void duplicateOfForeignClass_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String rejectedName = "Sollte-Nicht-Entstehen-" + unique;
        Long foreignClassId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Dup-Fremd-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "teacherB-" + unique;
            owner.persist();
            return foreign.id;
        });

        HttpResponse<String> response = post("/classes/" + foreignClassId + "/duplicate",
                Map.of("name", rejectedName, "schoolYear", "2026/27"));

        assertEquals(404, response.statusCode());
        assertTrue(SchoolClass.find("name", rejectedName).firstResultOptional().isEmpty());
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
    }

    private Long createSubject(Long classId, String name, Long gradeScaleId) throws Exception {
        post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        Subject created = Subject.find("name", name).firstResult();
        return created.id;
    }

    private Long createCategory(Long subjectId, String name) throws Exception {
        post("/subjects/" + subjectId + "/categories", Map.of("name", name, "weightPercent", "100"));
        GradeCategory created = GradeCategory.find("name", name).firstResult();
        return created.id;
    }

    private Long createAssessment(Long subjectId, Long categoryId, String name) throws Exception {
        post("/subjects/" + subjectId + "/categories/" + categoryId + "/assessments",
                Map.of("name", name, "factor", "1"));
        Assessment created = Assessment.find("name", name).firstResult();
        return created.id;
    }

    private Long createStudent(Long classId, String name) throws Exception {
        post("/classes/" + classId + "/students", Map.of("name", name, "displayName", ""));
        Student created = Student.find("name", name).firstResult();
        return created.id;
    }

    private void saveGrade(Long subjectId, Long studentId, Long assessmentId, String value) throws Exception {
        post("/subjects/" + subjectId + "/grid/cell", Map.of(
                "studentId", String.valueOf(studentId),
                "assessmentId", String.valueOf(assessmentId),
                "value", value));
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
