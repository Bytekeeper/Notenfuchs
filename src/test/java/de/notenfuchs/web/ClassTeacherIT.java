package de.notenfuchs.web;

import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Teacher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level coverage for adding/removing class co-owners ({@link ClassUiResource#addClassTeacher}/
 * {@link ClassUiResource#removeClassTeacher}). A Failsafe IT (./mvnw verify) since it needs a real
 * Postgres, same reasoning as {@link ClassDuplicationIT} - whose plain {@code
 * java.net.http.HttpClient} style and "seed a foreign tenant via {@code
 * QuarkusTransaction.requiringNew()}" pattern this reuses directly.
 *
 * <p>Runs under the normal %test profile (no {@link LocalAuthTestProfile}/real login needed):
 * every request here acts as the fixed "dev-user" subject (see {@code
 * CurrentUser#effectiveSubject()}), and "colleague" teachers are seeded directly as {@link
 * Teacher} rows via Panache rather than depending on {@link
 * de.notenfuchs.security.TeacherDirectoryRecorder} actually firing - that mechanism has its own
 * dedicated coverage in {@code TeacherDirectoryRecorderIT}.
 */
@QuarkusTest
class ClassTeacherIT {

    @TestHTTPResource("/")
    URL rootUrl;

    private final TestHttp http = new TestHttp(() -> rootUrl);

    @Test
    void addClassTeacher_knownColleagueNotYetOnClass_grantsAccess() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-Add-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-" + unique);

        HttpResponse<String> response = http.post("/classes/" + classId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));

        assertEquals(200, response.statusCode());
        assertEquals(1, ClassTeacher.count("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject));
    }

    @Test
    void addClassTeacher_unknownSubject_returnsBadRequest() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-Unknown-Klasse-" + unique, "2025/26");

        HttpResponse<String> response = http.post("/classes/" + classId + "/teachers",
                Map.of("teacherSubject", "never-logged-in-" + unique));

        assertEquals(400, response.statusCode());
        assertEquals(1, ClassTeacher.count("schoolClass.id", classId));
    }

    @Test
    void addClassTeacher_alreadyAMember_returnsBadRequest() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-Duplicate-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-dup-" + unique);
        assertEquals(200, http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject)).statusCode());

        HttpResponse<String> response = http.post("/classes/" + classId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));

        assertEquals(400, response.statusCode());
        assertEquals(1, ClassTeacher.count("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject));
    }

    @Test
    void addAndRemoveClassTeacher_onForeignClass_bothReturnNotFound() throws Exception {
        String unique = Long.toString(System.nanoTime());
        String colleagueSubject = seedTeacher("colleague-foreign-" + unique);
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "Teacher-Foreign-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher owner = new ClassTeacher();
            owner.schoolClass = foreign;
            owner.teacherSubject = "teacherB-" + unique;
            owner.persist();
            return new Long[]{foreign.id, owner.id};
        });
        Long foreignClassId = ids[0];
        Long foreignClassTeacherId = ids[1];

        HttpResponse<String> addResponse = http.post("/classes/" + foreignClassId + "/teachers",
                Map.of("teacherSubject", colleagueSubject));
        assertEquals(404, addResponse.statusCode());

        HttpResponse<String> removeResponse = http.delete("/classes/" + foreignClassId + "/teachers/" + foreignClassTeacherId);
        assertEquals(404, removeResponse.statusCode());
    }

    @Test
    void removeClassTeacher_lastRemainingOwner_isRejected() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-LastOwner-Klasse-" + unique, "2025/26");
        ClassTeacher soleOwner = ClassTeacher.find("schoolClass.id", classId).firstResult();
        Long soleOwnerId = soleOwner.id;

        HttpResponse<String> response = http.delete("/classes/" + classId + "/teachers/" + soleOwnerId);

        assertEquals(400, response.statusCode());
        assertEquals(1, ClassTeacher.count("schoolClass.id", classId));
    }

    @Test
    void removeClassTeacher_anotherCoOwner_succeedsAndReturnsFragment() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-RemoveOther-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-remove-" + unique);
        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject));
        ClassTeacher colleagueClassTeacher = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject)
                .firstResult();
        Long colleagueClassTeacherId = colleagueClassTeacher.id;

        HttpResponse<String> response = http.delete("/classes/" + classId + "/teachers/" + colleagueClassTeacherId);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("HX-Redirect").isEmpty());
        assertTrue(response.body().contains("class-teachers-fragment"));
        assertEquals(0, ClassTeacher.count("id", colleagueClassTeacherId));
        assertEquals(1, ClassTeacher.count("schoolClass.id", classId));
    }

    @Test
    void removeClassTeacher_self_redirectsWithNoFragmentBody() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-RemoveSelf-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-self-" + unique);
        // Explicitly ADMIN, not the default FACHLEHRER - this test is about self-removal while
        // another admin remains, not the last-admin guardrail (see the dedicated test for that).
        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject, "role", "ADMIN"));
        // dev-user is the class's creator/owner (see CurrentUser#effectiveSubject in %test).
        ClassTeacher ownSelfClassTeacher = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, "dev-user")
                .firstResult();
        Long ownSelfClassTeacherId = ownSelfClassTeacher.id;

        HttpResponse<String> response = http.delete("/classes/" + classId + "/teachers/" + ownSelfClassTeacherId);

        assertEquals(200, response.statusCode());
        assertEquals("/classes", response.headers().firstValue("HX-Redirect").orElse(null));
        assertTrue(response.body().isEmpty());
        assertFalse(ClassTeacher.count("id", ownSelfClassTeacherId) > 0);
        assertEquals(1, ClassTeacher.count("schoolClass.id", classId));
    }

    @Test
    void addClassTeacher_blankRole_defaultsToFachlehrer() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-DefaultRole-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-defaultrole-" + unique);

        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject));

        ClassTeacher colleagueClassTeacher = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject)
                .firstResult();
        assertEquals(ClassTeacherRole.FACHLEHRER, colleagueClassTeacher.role);
    }

    @Test
    void addClassTeacher_explicitAdminRole_isPersisted() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-ExplicitAdmin-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-explicitadmin-" + unique);

        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject, "role", "ADMIN"));

        ClassTeacher colleagueClassTeacher = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject)
                .firstResult();
        assertEquals(ClassTeacherRole.ADMIN, colleagueClassTeacher.role);
    }

    @Test
    void removeClassTeacher_lastAdmin_isRejected_evenWithFachlehrerTierRowPresent() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-LastAdminWithFachlehrer-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-lastadmin-" + unique);
        // Defaults to FACHLEHRER - does not count toward the admin guardrail.
        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject));
        // dev-user is the class's sole ADMIN (creator).
        ClassTeacher soleAdmin = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, "dev-user")
                .firstResult();

        HttpResponse<String> response = http.delete("/classes/" + classId + "/teachers/" + soleAdmin.id);

        assertEquals(400, response.statusCode());
        assertEquals(2, ClassTeacher.count("schoolClass.id", classId));
    }

    @Test
    void removeClassTeacher_fachlehrerTierRow_neverBlockedByAdminGuardrail() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long classId = createClass("Teacher-RemoveFachlehrer-Klasse-" + unique, "2025/26");
        String colleagueSubject = seedTeacher("colleague-removefachlehrer-" + unique);
        http.post("/classes/" + classId + "/teachers", Map.of("teacherSubject", colleagueSubject));
        ClassTeacher colleagueClassTeacher = ClassTeacher
                .find("schoolClass.id = ?1 and teacherSubject = ?2", classId, colleagueSubject)
                .firstResult();
        assertEquals(ClassTeacherRole.FACHLEHRER, colleagueClassTeacher.role);

        // Only one ADMIN exists (dev-user) alongside this FACHLEHRER row, yet removing the
        // FACHLEHRER row is never gated by the admin count - that guardrail only protects ADMIN rows.
        HttpResponse<String> response = http.delete("/classes/" + classId + "/teachers/" + colleagueClassTeacher.id);

        assertEquals(200, response.statusCode());
        assertEquals(1, ClassTeacher.count("schoolClass.id", classId));
    }

    /**
     * The class list (/classes) shows every class the viewer has any kind of access to (via
     * {@link OwnershipGuard#listAccessibleClasses}), including classes where they're only a
     * {@code FACHLEHRER}-tier {@link ClassTeacher} - but {@link ClassUiResource#rename}/{@code
     * #delete} are {@code requireClassAdmin}-gated, so {@code fragments/home.html} must hide
     * Ändern/Löschen for those rows rather than offering a control that would 404.
     */
    @Test
    void classList_hidesRenameAndDeleteForClassesWhereViewerIsNotAdmin() throws Exception {
        String unique = Long.toString(System.nanoTime());
        Long adminClassId = createClass("List-Admin-Klasse-" + unique, "2025/26");
        Long fachlehrerClassId = QuarkusTransaction.requiringNew().call(() -> {
            SchoolClass foreign = new SchoolClass();
            foreign.name = "List-Fachlehrer-Klasse-" + unique;
            foreign.schoolYear = "2025/26";
            foreign.persist();
            ClassTeacher admin = new ClassTeacher();
            admin.schoolClass = foreign;
            admin.teacherSubject = "teacherB-" + unique;
            admin.role = ClassTeacherRole.ADMIN;
            admin.persist();
            ClassTeacher devUserAsFachlehrer = new ClassTeacher();
            devUserAsFachlehrer.schoolClass = foreign;
            devUserAsFachlehrer.teacherSubject = "dev-user";
            devUserAsFachlehrer.role = ClassTeacherRole.FACHLEHRER;
            devUserAsFachlehrer.persist();
            return foreign.id;
        });

        String html = http.get("/classes").body();

        assertTrue(html.contains("List-Admin-Klasse-" + unique));
        assertTrue(html.contains("hx-delete=\"/classes/" + adminClassId + "\""));
        assertTrue(html.contains("List-Fachlehrer-Klasse-" + unique));
        assertFalse(html.contains("hx-delete=\"/classes/" + fachlehrerClassId + "\""));
        assertFalse(html.contains("hx-patch=\"/classes/" + fachlehrerClassId + "/rename\""));
    }

    private String seedTeacher(String subject) {
        QuarkusTransaction.requiringNew().run(() -> {
            Teacher teacher = new Teacher();
            teacher.subject = subject;
            teacher.displayName = subject;
            teacher.firstSeenAt = Instant.now();
            teacher.lastSeenAt = Instant.now();
            teacher.persist();
        });
        return subject;
    }

    private Long createClass(String name, String schoolYear) throws Exception {
        http.post("/classes", Map.of("name", name, "schoolYear", schoolYear));
        SchoolClass created = SchoolClass.find("name", name).firstResult();
        return created.id;
    }
}
