package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.Grade;
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

import java.math.BigDecimal;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@link ClassOverviewResource} - the ADMIN-tier-only, read-only
 * class-wide grade overview ("See all final grades of Fächer and final grade of students" from
 * the three-tier access model). A Failsafe IT (./mvnw verify) - needs a real Postgres, same
 * reasoning as {@link ClassTeacherIT}. Every request acts as the fixed "dev-user" subject (see
 * {@code CurrentUser#effectiveSubject()} under %test); non-admin scenarios seed a *foreign* class
 * and attach dev-user to it only as far as the scenario needs, same pattern as
 * {@link SubjectStructuralAccessIT}.
 */
@QuarkusTest
class ClassOverviewIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void overview_admin_showsFinalGradesForEveryStudentAndSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Overview-Klasse-" + unique, "2025/26");
        String subjectAName = "Overview-FachA-" + unique;
        String subjectBName = "Overview-FachB-" + unique;
        Long subjectAId = createSubject(classId, subjectAName);
        createSubject(classId, subjectBName);
        String studentName = "Overview-Schueler-" + unique;
        Long studentId = createStudent(classId, studentName);

        // A single graded Leistung in FachA only - FachB stays ungraded ("-").
        QuarkusTransaction.requiringNew().run(() -> {
            Subject subjectA = Subject.<Subject>findById(subjectAId);
            Student student = Student.<Student>findById(studentId);
            GradeCategory category = new GradeCategory();
            category.subject = subjectA;
            category.name = "Schriftlich";
            category.weightPercent = new BigDecimal("100");
            category.persist();
            Assessment assessment = new Assessment();
            assessment.category = category;
            assessment.name = "Klassenarbeit 1";
            assessment.factor = BigDecimal.ONE;
            assessment.persist();
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = student;
            grade.value = new BigDecimal("2");
            grade.persist();
        });

        HttpResponse<String> response = http.get("/classes/" + classId + "/overview");

        assertEquals(200, response.statusCode());
        String html = response.body();
        assertTrue(html.contains(studentName));
        assertTrue(html.contains(subjectAName));
        assertTrue(html.contains(subjectBName));
        assertTrue(html.contains(">2<"));
    }

    @Test
    void overview_marksAtRiskGradesAndCountsWhatIsStillMissing() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Risk-Klasse-" + unique, "2025/26");
        Long subjectId = createSubject(classId, "Risk-Fach-" + unique);
        Long weakId = createStudent(classId, "Risk-Schwach-" + unique);
        createStudent(classId, "Risk-Ohne-" + unique);

        // One due Leistung: the first student has a 5 (at risk on DE 1-6), the second nothing yet.
        QuarkusTransaction.requiringNew().run(() -> {
            GradeCategory category = new GradeCategory();
            category.subject = Subject.<Subject>findById(subjectId);
            category.name = "Schriftlich";
            category.weightPercent = new BigDecimal("100");
            category.persist();
            Assessment assessment = new Assessment();
            assessment.category = category;
            assessment.name = "Klassenarbeit 1";
            assessment.factor = BigDecimal.ONE;
            assessment.date = java.time.LocalDate.now().minusDays(1);
            assessment.persist();
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = Student.<Student>findById(weakId);
            grade.value = new BigDecimal("5");
            grade.persist();
        });

        String html = http.get("/classes/" + classId + "/overview").body();

        assertTrue(html.contains("average-cell at-risk"), html);
        assertTrue(html.contains("<strong>1</strong> Schüler ist in mindestens einem Fach gefährdet"));
        // Footer: one grade and two Verhaltensnoten still missing in the one Fach.
        assertTrue(html.contains("status-open\">1</td>"), html);
        assertTrue(html.contains("status-open\">2</td>"), html);
    }

    @Test
    void overview_classLevelFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Fremd-Fachlehrer-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            ClassTeacher classFachlehrer = new ClassTeacher();
            classFachlehrer.schoolClass = foreign;
            classFachlehrer.teacherSubject = "dev-user";
            classFachlehrer.role = ClassTeacherRole.FACHLEHRER;
            classFachlehrer.persist();
            return foreign.id;
        });

        HttpResponse<String> response = http.get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
    }

    @Test
    void overview_subjectOnlyFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Fremd-Subjektlehrer-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "Overview-Fremd-Fach-" + unique;
            subject.gradeScale = gradeScale;
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "dev-user";
            subjectTeacher.persist();
            return foreign.id;
        });

        HttpResponse<String> response = http.get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
    }

    @Test
    void overview_foreignClass_returnsNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Overview-Komplett-Fremd-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            return foreign.id;
        });

        HttpResponse<String> response = http.get("/classes/" + classId + "/overview");

        assertEquals(404, response.statusCode());
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
    }

    private Long createSubject(Long classId, String name) throws Exception {
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", name, "gradeScaleId", String.valueOf(gradeScaleId), "roundingMode", "COMMERCIAL"));
        return Subject.find("name", name).<Subject>firstResult().id;
    }

    private Long createStudent(Long classId, String name) throws Exception {
        http.post("/classes/" + classId + "/students", Map.of("name", name, "displayName", ""));
        return Student.find("name", name).<Student>firstResult().id;
    }
}
