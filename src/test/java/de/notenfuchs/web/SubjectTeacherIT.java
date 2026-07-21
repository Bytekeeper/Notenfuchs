package de.notenfuchs.web;

import de.notenfuchs.domain.ClassTeacher;
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

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for adding/removing Fachlehrer ({@link SubjectUiResource#addSubjectTeacher}/
 * {@link SubjectUiResource#removeSubjectTeacher}). Mirrors {@link ClassTeacherIT} closely (same
 * plain-{@code HttpClient} style, same "seed a foreign tenant via {@code
 * QuarkusTransaction.requiringNew()}" pattern, same "colleague" seeded directly as a {@link
 * Teacher} row rather than depending on {@code TeacherDirectoryRecorder}) - the one real
 * difference under test here is that this axis is deliberately **self-service**: any current
 * teacher of a subject can add/remove another, not just the class owner (see {@code
 * OwnershipGuard#requireTeachesSubjectTeacher} and CLAUDE.md's Authorization section). Several
 * tests below exist specifically to prove that isn't accidentally owner-gated.
 */
@QuarkusTest
class SubjectTeacherIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void addSubjectTeacher_knownColleagueNotYetOnSubject_grantsAccess() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = createClassAndSubject("SubjTeacher-Add-Klasse-" + unique, "SubjTeacher-Add-Fach-" + unique);
        String colleagueSubject = seedTeacher("colleague-" + unique);

        HttpResponse<String> response = post("/subjects/" + subjectId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));

        assertEquals(200, response.statusCode());
        assertEquals(1, SubjectTeacher.count("subject.id = ?1 and teacherSubject = ?2", subjectId, colleagueSubject));
    }

    @Test
    void addSubjectTeacher_unknownSubject_returnsBadRequest() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = createClassAndSubject("SubjTeacher-Unknown-Klasse-" + unique, "SubjTeacher-Unknown-Fach-" + unique);

        HttpResponse<String> response = post("/subjects/" + subjectId + "/teachers",
                Map.of("teacherSubject", "never-logged-in-" + unique));

        assertEquals(400, response.statusCode());
        assertEquals(1, SubjectTeacher.count("subject.id", subjectId));
    }

    @Test
    void addSubjectTeacher_alreadyAMember_returnsBadRequest() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = createClassAndSubject("SubjTeacher-Dup-Klasse-" + unique, "SubjTeacher-Dup-Fach-" + unique);
        String colleagueSubject = seedTeacher("colleague-dup-" + unique);
        assertEquals(200, post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueSubject)).statusCode());

        HttpResponse<String> response = post("/subjects/" + subjectId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));

        assertEquals(400, response.statusCode());
        assertEquals(1, SubjectTeacher.count("subject.id = ?1 and teacherSubject = ?2", subjectId, colleagueSubject));
    }

    @Test
    void addAndRemoveSubjectTeacher_onForeignSubject_bothReturnNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String colleagueSubject = seedTeacher("colleague-foreign-" + unique);
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "SubjTeacher-Foreign-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "ownerX-" + unique;
            owner.persist();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "SubjTeacher-Foreign-Fach-" + unique;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher subjectTeacher = new SubjectTeacher();
            subjectTeacher.subject = subject;
            subjectTeacher.teacherSubject = "ownerX-" + unique;
            subjectTeacher.persist();
            return new Long[]{subject.id, subjectTeacher.id};
        });
        Long foreignSubjectId = ids[0];
        Long foreignSubjectTeacherId = ids[1];

        HttpResponse<String> addResponse = post("/subjects/" + foreignSubjectId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));
        assertEquals(404, addResponse.statusCode());

        HttpResponse<String> removeResponse = delete("/subjects/" + foreignSubjectId + "/teachers/" + foreignSubjectTeacherId);
        assertEquals(404, removeResponse.statusCode());
    }

    @Test
    void removeSubjectTeacher_lastRemainingTeacher_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = createClassAndSubject("SubjTeacher-Last-Klasse-" + unique, "SubjTeacher-Last-Fach-" + unique);
        SubjectTeacher soleTeacher = SubjectTeacher.find("subject.id", subjectId).firstResult();
        Long soleTeacherId = soleTeacher.id;

        HttpResponse<String> response = delete("/subjects/" + subjectId + "/teachers/" + soleTeacherId);

        assertEquals(400, response.statusCode());
        assertEquals(1, SubjectTeacher.count("subject.id", subjectId));
    }

    @Test
    void removeSubjectTeacher_anotherTeacher_succeedsAndReturnsFragment() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = createClassAndSubject("SubjTeacher-RemoveOther-Klasse-" + unique, "SubjTeacher-RemoveOther-Fach-" + unique);
        String colleagueSubject = seedTeacher("colleague-remove-" + unique);
        post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueSubject));
        SubjectTeacher colleagueSubjectTeacher = SubjectTeacher
                .find("subject.id = ?1 and teacherSubject = ?2", subjectId, colleagueSubject)
                .firstResult();
        Long colleagueSubjectTeacherId = colleagueSubjectTeacher.id;

        HttpResponse<String> response = delete("/subjects/" + subjectId + "/teachers/" + colleagueSubjectTeacherId);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("HX-Redirect").isEmpty());
        assertTrue(response.body().contains("subject-teachers-fragment"));
        assertEquals(0, SubjectTeacher.count("id", colleagueSubjectTeacherId));
        assertEquals(1, SubjectTeacher.count("subject.id", subjectId));
    }

    @Test
    void removeSubjectTeacher_self_redirectsToClassPage_whenOwnerStillHasClassAccess() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String className = "SubjTeacher-SelfOwner-Klasse-" + unique;
        post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        SchoolClass createdClass = SchoolClass.find("name", className).firstResult();
        Long classId = createdClass.id;
        Long subjectId = createSubject(classId, "SubjTeacher-SelfOwner-Fach-" + unique);
        String colleagueSubject = seedTeacher("colleague-selfowner-" + unique);
        post("/subjects/" + subjectId + "/teachers", Map.of("teacherSubject", colleagueSubject));
        // dev-user is the class's creator/owner (see CurrentUser#effectiveSubject in %test) as
        // well as one of the subject's two teachers.
        SubjectTeacher ownSelfSubjectTeacher = SubjectTeacher
                .find("subject.id = ?1 and teacherSubject = ?2", subjectId, "dev-user")
                .firstResult();

        HttpResponse<String> response = delete("/subjects/" + subjectId + "/teachers/" + ownSelfSubjectTeacher.id);

        assertEquals(200, response.statusCode());
        assertEquals("/classes/" + classId, response.headers().firstValue("HX-Redirect").orElse(null));
        assertTrue(response.body().isEmpty());
        assertFalse(SubjectTeacher.count("id", ownSelfSubjectTeacher.id) > 0);
        // dev-user still owns the class itself (ClassTeacher untouched).
        assertEquals(1, ClassTeacher.count("schoolClass.id = ?1 and teacherSubject = 'dev-user'", classId));
    }

    @Test
    void removeSubjectTeacher_self_redirectsToClasses_whenNoAccessRemains() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "SubjTeacher-SelfNone-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "ownerX-" + unique;
            owner.persist();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "SubjTeacher-SelfNone-Fach-" + unique;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher ownerTeacher = new SubjectTeacher();
            ownerTeacher.subject = subject;
            ownerTeacher.teacherSubject = "ownerX-" + unique;
            ownerTeacher.persist();
            // dev-user teaches this one subject but owns nothing and teaches nothing else here.
            SubjectTeacher devUserTeacher = new SubjectTeacher();
            devUserTeacher.subject = subject;
            devUserTeacher.teacherSubject = "dev-user";
            devUserTeacher.persist();
            return subject.id;
        });

        SubjectTeacher ownSelfSubjectTeacher = SubjectTeacher
                .find("subject.id = ?1 and teacherSubject = ?2", subjectId, "dev-user")
                .firstResult();

        HttpResponse<String> response = delete("/subjects/" + subjectId + "/teachers/" + ownSelfSubjectTeacher.id);

        assertEquals(200, response.statusCode());
        assertEquals("/classes", response.headers().firstValue("HX-Redirect").orElse(null));
        assertTrue(response.body().isEmpty());
        assertFalse(SubjectTeacher.count("id", ownSelfSubjectTeacher.id) > 0);
    }

    @Test
    void addSubjectTeacher_nonOwnerCollaboratorTeachingSubject_canAdd() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String colleagueSubject = seedTeacher("colleague-nonowner-add-" + unique);
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "SubjTeacher-NonOwnerAdd-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "ownerX-" + unique;
            owner.persist();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "SubjTeacher-NonOwnerAdd-Fach-" + unique;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher devUserTeacher = new SubjectTeacher();
            devUserTeacher.subject = subject;
            devUserTeacher.teacherSubject = "dev-user"; // teaches, but owns nothing
            devUserTeacher.persist();
            return subject.id;
        });

        // dev-user is neither the class owner nor its creator, but teaches this subject - self-
        // service means that alone must be enough to add another teacher to it.
        HttpResponse<String> response = post("/subjects/" + subjectId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));

        assertEquals(200, response.statusCode());
        assertEquals(1, SubjectTeacher.count("subject.id = ?1 and teacherSubject = ?2", subjectId, colleagueSubject));
    }

    @Test
    void removeSubjectTeacher_nonOwnerCollaboratorTeachingSubject_canRemove() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "SubjTeacher-NonOwnerRemove-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "ownerX-" + unique;
            owner.persist();
            Subject subject = new Subject();
            subject.schoolClass = foreign;
            subject.name = "SubjTeacher-NonOwnerRemove-Fach-" + unique;
            subject.gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
            subject.roundingMode = RoundingMode.COMMERCIAL;
            subject.persist();
            SubjectTeacher devUserTeacher = new SubjectTeacher();
            devUserTeacher.subject = subject;
            devUserTeacher.teacherSubject = "dev-user"; // teaches, but owns nothing
            devUserTeacher.persist();
            SubjectTeacher colleagueTeacher = new SubjectTeacher();
            colleagueTeacher.subject = subject;
            colleagueTeacher.teacherSubject = "colleague-nonowner-remove-" + unique;
            colleagueTeacher.persist();
            return new Long[]{subject.id, colleagueTeacher.id};
        });
        Long subjectId = ids[0];
        Long colleagueSubjectTeacherId = ids[1];

        // dev-user removes the *colleague*, not itself - proving removal is equally self-service.
        HttpResponse<String> response = delete("/subjects/" + subjectId + "/teachers/" + colleagueSubjectTeacherId);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("HX-Redirect").isEmpty());
        assertEquals(0, SubjectTeacher.count("id", colleagueSubjectTeacherId));
        assertEquals(1, SubjectTeacher.count("subject.id", subjectId));
    }

    private String seedTeacher(String subject) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.email = subject + "@schule.de";
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
        return subject;
    }

    private Long createClassAndSubject(String className, String subjectName) throws Exception {
        post("/classes", Map.of("name", className, "schoolYear", "2025/26"));
        SchoolClass createdClass = SchoolClass.find("name", className).firstResult();
        return createSubject(createdClass.id, subjectName);
    }

    private Long createSubject(Long classId, String subjectName) throws Exception {
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
        post("/classes/" + classId + "/subjects", Map.of(
                "name", subjectName,
                "gradeScaleId", String.valueOf(gradeScale.id),
                "roundingMode", "COMMERCIAL"));
        Subject created = Subject.find("name", subjectName).firstResult();
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
