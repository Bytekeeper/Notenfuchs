package de.notenfuchs.rest;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * HTTP-level coverage for {@link SubjectResource}, the plain JSON REST API mirror of
 * {@code ClassUiResource}'s Subject endpoints. This had no dedicated test coverage at all before -
 * which is exactly how {@code create}/{@code update}/{@code delete} kept using the single-owner
 * model's {@code requireOwnedClass}/{@code requireOwnedSubject} calls mechanically renamed to the
 * weakest multi-teacher tier ({@code requireClassAccess}/{@code requireTeachesSubject}) instead of
 * the correct one ({@code requireClassTeacher}/{@code requireCanDeleteSubject}) - see {@code
 * SubjectStructuralAccessIT} for the equivalent, already-tested web-layer behavior this API is
 * meant to match.
 *
 * <p>Same pattern as {@code SubjectStructuralAccessIT}: every request acts as the fixed "dev-user"
 * %test subject (see {@code CurrentUser#effectiveSubject()}), so scoping dev-user down to a
 * specific access tier means seeding a class via direct Panache persistence with only as much
 * {@link ClassTeacher}/{@link SubjectTeacher} attached as the scenario needs, rather than going
 * through the app's own creation endpoints (which would always make dev-user a full ADMIN).
 */
@QuarkusTest
class SubjectResourceIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void create_subjectOnlyFachlehrer_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistClass("teacherB-" + unique, unique);
            Subject existing = persistSubject(foreign, unique);
            persistSubjectTeacher(existing, "dev-user");
            return foreign.id;
        });

        HttpResponse<String> response = postJson("/api/subjects", subjectJson(classId, "Neu-" + unique, "COMMERCIAL"));

        assertEquals(404, response.statusCode());
        assertEquals(0, Subject.count("name", "Neu-" + unique));
    }

    @Test
    void create_classLevelFachlehrer_succeeds() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistClass("teacherB-" + unique, unique);
            persistClassTeacher(foreign, "dev-user", ClassTeacherRole.FACHLEHRER);
            return foreign.id;
        });
        String subjectName = "Neu-Fachlehrer-" + unique;

        HttpResponse<String> response = postJson("/api/subjects", subjectJson(classId, subjectName, "COMMERCIAL"));

        assertEquals(201, response.statusCode());
        Subject created = Subject.find("name", subjectName).firstResult();
        assertEquals(classId, created.schoolClass.id);
        // The creator is automatically made a SubjectTeacher of their own new Subject.
        assertEquals(1, SubjectTeacher.count("subject.id = ?1 and teacherSubject = ?2", created.id, "dev-user"));
    }

    @Test
    void delete_subjectOnlyFachlehrer_isRejected_evenForOwnSubject() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = persistClass("teacherB-" + unique, unique);
            Subject subject = persistSubject(foreign, unique);
            persistSubjectTeacher(subject, "dev-user");
            return new Long[]{foreign.id, subject.id};
        });

        HttpResponse<String> response = delete("/api/subjects/" + ids[1]);

        assertEquals(404, response.statusCode());
        assertEquals(1, Subject.count("id", ids[1]));
    }

    @Test
    void delete_admin_succeedsEvenForSubjectTheyDontTeach() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass own = persistClass("dev-user", unique);
            Subject subject = persistSubject(own, unique);
            persistSubjectTeacher(subject, "someoneElse-" + unique);
            return new Long[]{own.id, subject.id};
        });

        HttpResponse<String> response = delete("/api/subjects/" + ids[1]);

        assertEquals(204, response.statusCode());
        assertEquals(0, Subject.count("id", ids[1]));
    }

    @Test
    void update_reparentIntoClassWithoutClassTeacherTier_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass own = persistClass("dev-user", unique);
            Subject subject = persistSubject(own, unique);
            persistSubjectTeacher(subject, "dev-user");

            // dev-user merely teaches an unrelated Subject in this other class - hasClassAccess,
            // but not requireClassTeacher tier, to it.
            SchoolClass foreignTarget = persistClass("teacherB-" + unique, unique + "-target");
            Subject foreignSubject = persistSubject(foreignTarget, unique + "-other");
            persistSubjectTeacher(foreignSubject, "dev-user");

            return new Long[]{subject.id, own.id, foreignTarget.id};
        });

        HttpResponse<String> response = putJson("/api/subjects/" + ids[0],
                subjectJson(ids[2], "Reparented-" + unique, "COMMERCIAL"));

        assertEquals(404, response.statusCode());
        Subject stillThere = Subject.findById(ids[0]);
        assertEquals(ids[1], stillThere.schoolClass.id);
        assertNotEquals(ids[2], stillThere.schoolClass.id);
    }

    @Test
    void update_omittedRoundingMode_defaultsToInFavorOfStudent() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass own = persistClass("dev-user", unique);
            Subject subject = persistSubject(own, unique);
            subject.roundingMode = RoundingMode.COMMERCIAL;
            persistSubjectTeacher(subject, "dev-user");
            return new Long[]{subject.id, own.id};
        });

        HttpResponse<String> response = putJson("/api/subjects/" + ids[0], String.format(
                "{\"schoolClassId\":%d,\"name\":\"Renamed-%s\",\"gradeScaleId\":%d}",
                ids[1], unique, gradeScaleId()));

        assertEquals(200, response.statusCode());
        Subject updated = Subject.findById(ids[0]);
        assertEquals(RoundingMode.IN_FAVOR_OF_STUDENT, updated.roundingMode);
    }

    private SchoolClass persistClass(String ownerSubject, String unique) {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.name = "Rest-Klasse-" + unique;
        schoolClass.schoolYear = "2025/26";
        schoolClass.persist();
        persistClassTeacher(schoolClass, ownerSubject, ClassTeacherRole.ADMIN);
        return schoolClass;
    }

    private Subject persistSubject(SchoolClass schoolClass, String unique) {
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = "Rest-Fach-" + unique;
        subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
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

    private String subjectJson(Long schoolClassId, String name, String roundingMode) {
        return String.format("{\"schoolClassId\":%d,\"name\":\"%s\",\"gradeScaleId\":%d,\"roundingMode\":\"%s\"}",
                schoolClassId, name, gradeScaleId(), roundingMode);
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
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

    private String baseUrl() {
        String url = rootUrl.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
