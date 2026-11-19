package de.notenfuchs.web;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import de.notenfuchs.domain.Teacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for {@code fragments/subjectList.html} showing which teacher(s) teach each
 * Fach ({@link ClassUiResource#attachTeacherLabels}) - lets a class admin see who to contact per
 * Fach, and notice which teachers have class access only via a {@link SubjectTeacher} row (not a
 * {@link de.notenfuchs.domain.ClassTeacher} row, so they're absent from the "Lehrkräfte" section).
 * Shown regardless of whether the viewer personally teaches the Fach. A Failsafe IT
 * (./mvnw verify) - needs a real Postgres, same reasoning as {@link ClassTeacherIT}.
 */
@QuarkusTest
class SubjectTeacherDisplayIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void subjectList_showsSoleTeacherAndNoCollapsibleWhenOnlyOne() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Solo-Klasse-" + unique, "2025/26");
        // dev-user creates the Fach, so is automatically its sole SubjectTeacher (see #addSubject).
        Long subjectId = createSubject(classId, "Display-Solo-Fach-" + unique);

        String html = http.get("/classes/" + classId).body();

        assertTrue(html.contains("dev-user"));
        assertFalse(html.contains("subject-teachers-more"));
    }

    @Test
    void subjectList_showsFirstTeacherPlusCollapsibleForTheRest_regardlessOfWhetherViewerTeachesIt() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Multi-Klasse-" + unique, "2025/26");
        Long subjectId = createSubject(classId, "Display-Multi-Fach-" + unique);
        String colleagueALabel = "kollegin-a-" + unique;
        String colleagueBLabel = "kollegin-b-" + unique;
        seedTeacher(colleagueALabel, colleagueALabel);
        seedTeacher(colleagueBLabel, colleagueBLabel);
        http.post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueALabel));
        http.post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueBLabel));

        String html = http.get("/classes/" + classId).body();

        assertTrue(html.contains("dev-user"));
        assertTrue(html.contains("subject-teachers-more"));
        assertTrue(html.contains("+2 weitere"));
        assertTrue(html.contains(colleagueALabel));
        assertTrue(html.contains(colleagueBLabel));
    }

    @Test
    void subjectList_showsTeacherInfoEvenForSubjectViewerDoesNotTeach() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Display-Untaught-Klasse-" + unique, "2025/26");
        String otherTeacherLabel = "kollegin-untaught-" + unique;
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass schoolClass = SchoolClass.<SchoolClass>findById(classId);
            GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            Subject subject = new Subject();
            subject.schoolClass = schoolClass;
            subject.name = "Display-Untaught-Fach-" + unique;
            subject.gradeScale = gradeScale;
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "colleague-untaught-" + unique;
            subjectTeacher.persist();
            Teacher teacher = new Teacher();
            teacher.subject = "colleague-untaught-" + unique;
            teacher.displayName = otherTeacherLabel;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
            return subject.id;
        });

        String html = http.get("/classes/" + classId).body();

        assertTrue(html.contains(otherTeacherLabel));
    }

    private void seedTeacher(String subject, String label) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.displayName = label;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
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
}
