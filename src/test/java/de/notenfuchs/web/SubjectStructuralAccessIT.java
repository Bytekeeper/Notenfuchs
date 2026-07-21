package de.notenfuchs.web;

import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP-level coverage for the three-tier split of {@link ClassUiResource#addSubject}/
 * {@link ClassUiResource#deleteSubject}: adding a new Fach needs a {@link ClassTeacher} row of
 * either role (not mere class access via teaching a subject), and deleting one needs either
 * {@code ADMIN} (any Fach) or a {@code FACHLEHRER}-tier row plus actually teaching that Fach - a
 * plain subject-only Fachlehrer (a lone {@link SubjectTeacher} row, no {@link ClassTeacher} row at
 * all) can do neither, even for a Fach they personally teach.
 *
 * <p>Every request here acts as the fixed "dev-user" subject (see {@code
 * CurrentUser#effectiveSubject()} under %test), so scoping dev-user down to a specific tier means
 * seeding a *foreign* class (owned by a different teacher subject) and attaching dev-user to it
 * only as far as the scenario needs - the same pattern {@code SubjectTeacherIT}'s
 * non-owner-collaborator tests already use. A Failsafe IT (./mvnw verify) - needs a real Postgres,
 * same reasoning as {@link ClassTeacherIT}.
 */
@QuarkusTest
class SubjectStructuralAccessIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void addSubject_subjectOnlyFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistForeignClass("teacherB-" + unique, unique);
            Subject subject = persistSubject(foreign, unique);
            persistSubjectTeacher(subject, "dev-user");
            return foreign.id;
        });

        HttpResponse<String> response = post("/classes/" + classId + "/subjects", Map.of(
                "name", "Neues-Fach-" + unique, "gradeScaleId", String.valueOf(gradeScaleId()), "roundingMode", "COMMERCIAL"));

        assertEquals(404, response.statusCode());
    }

    @Test
    void addSubject_classLevelFachlehrer_succeeds() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistForeignClass("teacherB-" + unique, unique);
            persistClassTeacher(foreign, "dev-user", ClassTeacherRole.FACHLEHRER);
            return foreign.id;
        });
        String subjectName = "Neues-Fach-Fachlehrer-" + unique;

        HttpResponse<String> response = post("/classes/" + classId + "/subjects", Map.of(
                "name", subjectName, "gradeScaleId", String.valueOf(gradeScaleId()), "roundingMode", "COMMERCIAL"));

        assertEquals(200, response.statusCode());
        assertEquals(1, Subject.count("schoolClass.id = ?1 and name = ?2", classId, subjectName));
    }

    @Test
    void deleteSubject_subjectOnlyFachlehrer_isRejected_evenForOwnSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistForeignClass("teacherB-" + unique, unique);
            Subject subject = persistSubject(foreign, unique);
            persistSubjectTeacher(subject, "dev-user");
            return new Long[]{foreign.id, subject.id};
        });

        HttpResponse<String> response = delete("/classes/" + ids[0] + "/subjects/" + ids[1]);

        assertEquals(404, response.statusCode());
        assertEquals(1, Subject.count("id", ids[1]));
    }

    @Test
    void deleteSubject_classLevelFachlehrer_succeedsForOwnSubject_butNotForeignSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistForeignClass("teacherB-" + unique, unique);
            persistClassTeacher(foreign, "dev-user", ClassTeacherRole.FACHLEHRER);
            Subject ownSubject = persistSubject(foreign, unique + "-own");
            persistSubjectTeacher(ownSubject, "dev-user");
            Subject foreignSubject = persistSubject(foreign, unique + "-foreign");
            persistSubjectTeacher(foreignSubject, "teacherB-" + unique);
            return new Long[]{foreign.id, ownSubject.id, foreignSubject.id};
        });

        HttpResponse<String> deleteForeign = delete("/classes/" + ids[0] + "/subjects/" + ids[2]);
        assertEquals(404, deleteForeign.statusCode());
        assertEquals(1, Subject.count("id", ids[2]));

        HttpResponse<String> deleteOwn = delete("/classes/" + ids[0] + "/subjects/" + ids[1]);
        assertEquals(200, deleteOwn.statusCode());
        assertEquals(0, Subject.count("id", ids[1]));
    }

    @Test
    void deleteSubject_admin_succeedsEvenForSubjectTheyDontTeach() throws Exception {
        String unique = Long.toString(System.nanoTime());
        // dev-user creates this class normally, so is its ADMIN (see ClassUiResource#create).
        Long classId = createClass("Admin-Delete-Klasse-" + unique, "2025/26");
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass schoolClass = SchoolClass.<SchoolClass>findById(classId);
            Subject subject = persistSubject(schoolClass, unique);
            persistSubjectTeacher(subject, "someoneElse-" + unique);
            return subject.id;
        });

        HttpResponse<String> response = delete("/classes/" + classId + "/subjects/" + subjectId);

        assertEquals(200, response.statusCode());
        assertEquals(0, Subject.count("id", subjectId));
    }

    private SchoolClass persistForeignClass(String ownerSubject, String unique) {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.name = "Structural-Fremd-Klasse-" + unique;
        schoolClass.schoolYear = "2025/26";
        schoolClass.persist();
        persistClassTeacher(schoolClass, ownerSubject, ClassTeacherRole.ADMIN);
        return schoolClass;
    }

    private Subject persistSubject(SchoolClass schoolClass, String unique) {
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = "Structural-Fach-" + unique;
        subject.gradeScale = gradeScale;
        subject.roundingMode = RoundingMode.COMMERCIAL;
        subject.persist();
        return subject;
    }

    private void persistSubjectTeacher(Subject subject, String teacherSubject) {
        SubjectTeacher subjectTeacher = new SubjectTeacher();
        subjectTeacher.subject = subject;
        subjectTeacher.teacherSubject = teacherSubject;
        subjectTeacher.persist();
    }

    private void persistClassTeacher(SchoolClass schoolClass, String teacherSubject, ClassTeacherRole role) {
        ClassTeacher classTeacher = new ClassTeacher();
        classTeacher.schoolClass = schoolClass;
        classTeacher.teacherSubject = teacherSubject;
        classTeacher.role = role;
        classTeacher.persist();
    }

    private Long gradeScaleId() {
        return GradeScale.find("name", "DE 1-6").<GradeScale>firstResult().id;
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
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

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .DELETE()
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
