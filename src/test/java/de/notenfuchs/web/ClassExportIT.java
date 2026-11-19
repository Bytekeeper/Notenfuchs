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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The class-wide xlsx ({@code ClassExportResource}): an admin gets the final-grade overview of
 * every Fach plus full sheets only for Fächer they teach; others get only their own Fächer.
 */
@QuarkusTest
class ClassExportIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void admin_getsOverviewOfEveryFach_butFullSheetsOnlyForOwnFaecher() throws Exception {
        String unique = Long.toString(System.nanoTime()).substring(8);
        http.post("/classes", Map.of("name", "Export-" + unique, "schoolYear", "2025/26"));
        Long classId = SchoolClass.find("name", "Export-" + unique).<SchoolClass>firstResult().id;
        Long gradeScaleId = GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
        http.post("/classes/" + classId + "/subjects",
                Map.of("name", "Eigen-" + unique, "gradeScaleId", gradeScaleId.toString(), "roundingMode", "COMMERCIAL"));
        http.post("/classes/" + classId + "/students", Map.of("name", "Anna-" + unique));
        Long annaId = Student.find("name", "Anna-" + unique).<Student>firstResult().id;
        seedFachTaughtByColleagueWithGrade(classId, "Fremd-" + unique, annaId, "3");

        Workbook workbook = export(classId);

        assertEquals(2, workbook.getNumberOfSheets());
        Sheet overview = workbook.getSheet(ClassExportResource.OVERVIEW_SHEET);
        assertNotNull(overview);
        assertNotNull(workbook.getSheet("Eigen-" + unique));
        assertNull(workbook.getSheet("Fremd-" + unique));
        // Overview columns are the Fächer by name: Eigen, Fremd. Anna's final grade in Fremd is 3.
        Row header = overview.getRow(0);
        assertEquals("Fremd-" + unique, header.getCell(2).getStringCellValue());
        assertEquals(3.0, overview.getRow(1).getCell(2).getNumericCellValue());
    }

    @Test
    void subjectOnlyFachlehrer_getsOnlyTheirOwnFach() throws Exception {
        String unique = Long.toString(System.nanoTime()).substring(8);
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass schoolClass = foreignClass("Export-Fremd-" + unique);
            Subject subject = subject(schoolClass, "Mein-" + unique);
            teach(subject, "dev-user");
            teach(subject(schoolClass, "Andere-" + unique), "colleague-" + unique);
            return schoolClass.id;
        });

        Workbook workbook = export(classId);

        assertEquals(1, workbook.getNumberOfSheets());
        assertEquals("Mein-" + unique, workbook.getSheetAt(0).getSheetName());
    }

    @Test
    void classWithoutAccess_isNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime()).substring(8);
        Long classId = QuarkusTransaction.requiringNew().call(() -> foreignClass("Export-Zu-" + unique).id);

        assertEquals(404, http.getBytes("/classes/" + classId + "/export").statusCode());
    }

    @Test
    void sheetNames_stayUniqueAndShortEnoughForExcel() {
        java.util.Set<String> used = new java.util.HashSet<>(java.util.Set.of("notenübersicht"));
        assertEquals("Notenübersicht (2)", ClassExportResource.uniqueSheetName("Notenübersicht", used));
        String longName = "Ein sehr langer Fachname für Excel";
        String first = ClassExportResource.uniqueSheetName(longName, used);
        String second = ClassExportResource.uniqueSheetName(longName, used);
        assertEquals(31, first.length());
        assertEquals(31, second.length());
        assertEquals(" (2)", second.substring(27));
    }

    private Workbook export(Long classId) throws Exception {
        HttpResponse<byte[]> response = http.getBytes("/classes/" + classId + "/export");
        assertEquals(200, response.statusCode());
        return WorkbookFactory.create(new ByteArrayInputStream(response.body()));
    }

    private static void seedFachTaughtByColleagueWithGrade(Long classId, String name, Long studentId, String value) {
        QuarkusTransaction.requiringNew().run(() -> {
            Subject subject = subject(SchoolClass.findById(classId), name);
            teach(subject, "colleague-" + name);
            GradeCategory category = new GradeCategory();
            category.subject = subject;
            category.name = "Schriftlich";
            category.weightPercent = new BigDecimal("100");
            category.persist();
            Assessment assessment = new Assessment();
            assessment.category = category;
            assessment.name = "KA";
            assessment.factor = BigDecimal.ONE;
            assessment.persist();
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = Student.findById(studentId);
            grade.value = new BigDecimal(value);
            grade.persist();
        });
    }

    private static SchoolClass foreignClass(String name) {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.name = name;
        schoolClass.schoolYear = "2025/26";
        schoolClass.persist();
        ClassTeacher admin = new ClassTeacher();
        admin.schoolClass = schoolClass;
        admin.teacherSubject = "admin-" + name;
        admin.role = ClassTeacherRole.ADMIN;
        admin.persist();
        return schoolClass;
    }

    private static Subject subject(SchoolClass schoolClass, String name) {
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = name;
        subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
        subject.roundingMode = RoundingMode.COMMERCIAL;
        subject.persist();
        return subject;
    }

    private static void teach(Subject subject, String teacherSubject) {
        SubjectTeacher subjectTeacher = new SubjectTeacher();
        subjectTeacher.subject = subject;
        subjectTeacher.teacherSubject = teacherSubject;
        subjectTeacher.persist();
    }
}
