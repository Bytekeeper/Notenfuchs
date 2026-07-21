package de.notenfuchs.security;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link OwnershipGuard}, the central point every REST/web resource routes through
 * instead of raw {@code findById}/{@code listAll}. Needs a real Postgres (Testcontainers Dev
 * Services, see application.properties), so this is a Failsafe IT (./mvnw verify), not a plain
 * unit test - same reasoning as the browser ITs in {@code src/test/java/de/notenfuchs/e2e}.
 *
 * <p>Covers both axes of the multi-teacher model: {@link ClassTeacher} (full class ownership,
 * "teacherA"/"teacherB" as distinct owners) and {@link SubjectTeacher} (per-subject teaching
 * rights, "collaborator" as a teacher who has access to a class only via one subject in it, not
 * as an owner). {@code @TestTransaction} rolls each test back afterwards, so tests don't need
 * unique names to stay independent of each other or of pre-existing data.
 */
@QuarkusTest
class OwnershipGuardIT {

    @Inject
    OwnershipGuard guard;

    @Test
    @TestTransaction
    void listAccessibleClasses_excludesClassesOwnedByAnotherTeacher() {
        SchoolClass a = persistClass("teacherA");
        SchoolClass b = persistClass("teacherB");

        List<SchoolClass> accessibleToA = guard.listAccessibleClasses("teacherA");

        assertTrue(accessibleToA.stream().anyMatch(c -> c.id.equals(a.id)));
        assertTrue(accessibleToA.stream().noneMatch(c -> c.id.equals(b.id)));
    }

    @Test
    @TestTransaction
    void listAccessibleClasses_includesClassesAccessibleOnlyViaSubjectTeaching() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");
        persistSubjectTeacher(subject, "collaborator");

        List<SchoolClass> accessibleToCollaborator = guard.listAccessibleClasses("collaborator");

        assertTrue(accessibleToCollaborator.stream().anyMatch(c -> c.id.equals(b.id)));
    }

    @Test
    @TestTransaction
    void requireClassAccess_ownClass_returnsIt() {
        SchoolClass a = persistClass("teacherA");

        SchoolClass found = guard.requireClassAccess(a.id, "teacherA");

        assertEquals(a.id, found.id);
    }

    @Test
    @TestTransaction
    void requireClassAccess_viaSubjectTeaching_returnsItButRequireClassOwnerRejects() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");
        persistSubjectTeacher(subject, "collaborator");

        SchoolClass found = guard.requireClassAccess(b.id, "collaborator");
        assertEquals(b.id, found.id);

        assertThrows(NotFoundException.class, () -> guard.requireClassAdmin(b.id, "collaborator"));
    }

    @Test
    @TestTransaction
    void requireClassAccess_foreignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");

        assertThrows(NotFoundException.class, () -> guard.requireClassAccess(b.id, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireClassAccess_unknownId_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> guard.requireClassAccess(-1L, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireClassAdmin_foreignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");

        assertThrows(NotFoundException.class, () -> guard.requireClassAdmin(b.id, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireTeachesSubject_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");

        assertThrows(NotFoundException.class, () -> guard.requireTeachesSubject(subject.id, "teacherA"));
        assertEquals(subject.id, guard.requireTeachesSubject(subject.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireTeachesSubject_classAccessButNotTeachingThisSubject_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject taughtByCollaborator = persistSubject(b, "teacherB");
        persistSubjectTeacher(taughtByCollaborator, "collaborator");
        Subject taughtOnlyByOwner = persistSubject(b, "teacherB");

        // Has class access (teaches a different subject in the same class)...
        assertEquals(b.id, guard.requireClassAccess(b.id, "collaborator").id);
        // ...but not Leistung-level access to a subject they don't personally teach, even though
        // the class owner does - this is the scenario that didn't exist before this model.
        assertThrows(NotFoundException.class,
                () -> guard.requireTeachesSubject(taughtOnlyByOwner.id, "collaborator"));
        assertEquals(taughtByCollaborator.id,
                guard.requireTeachesSubject(taughtByCollaborator.id, "collaborator").id);
    }

    @Test
    @TestTransaction
    void requireClassAccessStudent_and_requireRosterManageStudent_distinguishOwnerFromCollaborator() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");
        persistSubjectTeacher(subject, "collaborator");
        Student student = new Student();
        student.schoolClass = b;
        student.name = "Schueler";
        student.persist();

        assertEquals(student.id, guard.requireClassAccessStudent(student.id, "collaborator").id);
        assertThrows(NotFoundException.class, () -> guard.requireRosterManageStudent(student.id, "collaborator"));
        assertEquals(student.id, guard.requireRosterManageStudent(student.id, "teacherB").id);

        assertThrows(NotFoundException.class, () -> guard.requireClassAccessStudent(student.id, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireTeachesCategoryAndAssessment_belongingToForeignClass_throwNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");
        GradeCategory category = new GradeCategory();
        category.subject = subject;
        category.name = "Schriftlich";
        category.weightPercent = new BigDecimal("100");
        category.persist();
        Assessment assessment = new Assessment();
        assessment.category = category;
        assessment.name = "Klassenarbeit 1";
        assessment.factor = BigDecimal.ONE;
        assessment.persist();

        assertThrows(NotFoundException.class, () -> guard.requireTeachesCategory(category.id, "teacherA"));
        assertThrows(NotFoundException.class, () -> guard.requireTeachesAssessment(assessment.id, "teacherA"));
        assertEquals(category.id, guard.requireTeachesCategory(category.id, "teacherB").id);
        assertEquals(assessment.id, guard.requireTeachesAssessment(assessment.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireTeachesPointsGradeBand_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "teacherB");
        GradeCategory category = new GradeCategory();
        category.subject = subject;
        category.name = "Schriftlich";
        category.weightPercent = new BigDecimal("100");
        category.persist();
        Assessment assessment = new Assessment();
        assessment.category = category;
        assessment.name = "Klausur 1";
        assessment.factor = BigDecimal.ONE;
        assessment.pointsBased = true;
        assessment.persist();
        PointsGradeBand band = new PointsGradeBand();
        band.assessment = assessment;
        band.points = new BigDecimal("50");
        band.gradeValue = new BigDecimal("4");
        band.persist();

        assertThrows(NotFoundException.class, () -> guard.requireTeachesPointsGradeBand(band.id, "teacherA"));
        assertEquals(band.id, guard.requireTeachesPointsGradeBand(band.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireClassAccessBehaviorGrade_visibleClassWide_butRequireTeachesBehaviorGradeIsSubjectScoped() {
        SchoolClass b = persistClass("teacherB");
        Subject subjectA = persistSubject(b, "teacherB");
        persistSubjectTeacher(subjectA, "collaborator");
        Student student = new Student();
        student.schoolClass = b;
        student.name = "Fremder Schueler";
        student.persist();
        BehaviorGrade behaviorGrade = new BehaviorGrade();
        behaviorGrade.student = student;
        behaviorGrade.subject = subjectA;
        behaviorGrade.value = new BigDecimal("2");
        behaviorGrade.persist();

        assertThrows(NotFoundException.class, () -> guard.requireClassAccessBehaviorGrade(behaviorGrade.id, "teacherA"));
        assertEquals(behaviorGrade.id, guard.requireClassAccessBehaviorGrade(behaviorGrade.id, "teacherB").id);

        // A collaborator who teaches a *different* subject in the same class can still see this
        // Verhaltensnote (class-wide visibility)...
        Subject subjectB = persistSubject(b, "teacherB");
        persistSubjectTeacher(subjectB, "otherCollaborator");
        BehaviorGrade otherSubjectGrade = new BehaviorGrade();
        otherSubjectGrade.student = student;
        otherSubjectGrade.subject = subjectB;
        otherSubjectGrade.value = new BigDecimal("3");
        otherSubjectGrade.persist();
        assertEquals(otherSubjectGrade.id,
                guard.requireClassAccessBehaviorGrade(otherSubjectGrade.id, "collaborator").id);
        // ...but can't edit it, since they don't teach subjectB.
        assertThrows(NotFoundException.class,
                () -> guard.requireTeachesBehaviorGrade(otherSubjectGrade.id, "collaborator"));
        assertEquals(otherSubjectGrade.id,
                guard.requireTeachesBehaviorGrade(otherSubjectGrade.id, "otherCollaborator").id);
    }

    @Test
    @TestTransaction
    void requireClassAdminTeacher_foreignClassTeacher_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        ClassTeacher classTeacher = ClassTeacher.find("schoolClass", b).firstResult();

        assertThrows(NotFoundException.class, () -> guard.requireClassAdminTeacher(classTeacher.id, "teacherA"));
        assertEquals(classTeacher.id, guard.requireClassAdminTeacher(classTeacher.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireTeachesSubjectTeacher_nonTeacherOfSubject_throwsNotFound_butAnyCurrentTeacherCanResolveIt() {
        SchoolClass b = persistClass("teacherB");
        Subject subjectA = persistSubject(b, "teacherB");
        persistSubjectTeacher(subjectA, "collaborator");
        SubjectTeacher subjectTeacherRow = SubjectTeacher
                .find("subject = ?1 and teacherSubject = ?2", subjectA, "collaborator").firstResult();

        assertThrows(NotFoundException.class, () -> guard.requireTeachesSubjectTeacher(subjectTeacherRow.id, "teacherA"));
        // Unlike requireClassAdminTeacher (owner-only), a non-owner who teaches the subject also
        // resolves it - this is the self-service axis, not the class-ownership one.
        assertEquals(subjectTeacherRow.id, guard.requireTeachesSubjectTeacher(subjectTeacherRow.id, "collaborator").id);
        assertEquals(subjectTeacherRow.id, guard.requireTeachesSubjectTeacher(subjectTeacherRow.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void isAdmin_and_isClassTeacher_distinguishRoles() {
        SchoolClass b = persistClass("teacherB");
        persistClassTeacher(b, "classFachlehrer", ClassTeacherRole.FACHLEHRER);

        assertTrue(guard.isAdmin(b, "teacherB"));
        assertFalse(guard.isAdmin(b, "classFachlehrer"));

        assertTrue(guard.isClassTeacher(b, "teacherB"));
        assertTrue(guard.isClassTeacher(b, "classFachlehrer"));

        // hasClassAccess is broadened to either role, not admin-only.
        assertTrue(guard.hasClassAccess(b, "classFachlehrer"));
    }

    @Test
    @TestTransaction
    void requireClassAdmin_rejectsFachlehrerTierRow_butRequireClassTeacherAcceptsEitherRole() {
        SchoolClass b = persistClass("teacherB");
        persistClassTeacher(b, "classFachlehrer", ClassTeacherRole.FACHLEHRER);

        assertThrows(NotFoundException.class, () -> guard.requireClassAdmin(b.id, "classFachlehrer"));
        assertEquals(b.id, guard.requireClassTeacher(b.id, "classFachlehrer").id);
        assertEquals(b.id, guard.requireClassTeacher(b.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireCanDeleteSubject_adminAnyFach_classFachlehrerOwnFachOnly_subjectOnlyFachlehrerNever() {
        SchoolClass b = persistClass("teacherB");
        persistClassTeacher(b, "classFachlehrer", ClassTeacherRole.FACHLEHRER);
        // Taught by a colleague who has no ClassTeacher row at all on this class.
        Subject subjectNotTeacherBs = persistSubject(b, "someoneElse");
        // Taught by the class-level Fachlehrer.
        Subject subjectOwnedByClassFachlehrer = persistSubject(b, "classFachlehrer");
        // Taught only by a subject-only Fachlehrer (no ClassTeacher row anywhere).
        Subject subjectOwnedBySubjectOnlyFachlehrer = persistSubject(b, "subjectOnlyFachlehrer");

        // Admin deletes any Subject, even one they don't personally teach.
        assertEquals(subjectNotTeacherBs.id, guard.requireCanDeleteSubject(subjectNotTeacherBs.id, "teacherB").id);

        // Class-level Fachlehrer deletes a Subject they teach...
        assertEquals(subjectOwnedByClassFachlehrer.id,
                guard.requireCanDeleteSubject(subjectOwnedByClassFachlehrer.id, "classFachlehrer").id);
        // ...but not one they don't.
        assertThrows(NotFoundException.class,
                () -> guard.requireCanDeleteSubject(subjectNotTeacherBs.id, "classFachlehrer"));

        // A subject-only Fachlehrer (no ClassTeacher row) can't delete even their own Subject.
        assertThrows(NotFoundException.class,
                () -> guard.requireCanDeleteSubject(subjectOwnedBySubjectOnlyFachlehrer.id, "subjectOnlyFachlehrer"));
    }

    @Test
    @TestTransaction
    void requireTeachesSubject_adminNotTeachingSubject_stillThrowsNotFound_noAdminOverride() {
        // Confirmed explicitly with the user: an admin has no standing on a Subject they don't
        // personally teach - adding/removing a SubjectTeacher stays exclusively self-service.
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b, "someoneElse");

        assertThrows(NotFoundException.class, () -> guard.requireTeachesSubject(subject.id, "teacherB"));
    }

    private void persistClassTeacher(SchoolClass schoolClass, String teacherSubject, ClassTeacherRole role) {
        ClassTeacher classTeacher = new ClassTeacher();
        classTeacher.schoolClass = schoolClass;
        classTeacher.teacherSubject = teacherSubject;
        classTeacher.role = role;
        classTeacher.persist();
    }

    private SchoolClass persistClass(String owner) {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.name = "Ownership-Test-Klasse-" + owner + "-" + System.nanoTime();
        schoolClass.schoolYear = "2025/26";
        schoolClass.persist();

        ClassTeacher classTeacher = new ClassTeacher();
        classTeacher.schoolClass = schoolClass;
        classTeacher.teacherSubject = owner;
        classTeacher.persist();

        return schoolClass;
    }

    private Subject persistSubject(SchoolClass schoolClass, String teacherSubject) {
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = "Ownership-Test-Fach-" + System.nanoTime();
        subject.gradeScale = gradeScale;
        subject.roundingMode = RoundingMode.COMMERCIAL;
        subject.persist();
        persistSubjectTeacher(subject, teacherSubject);
        return subject;
    }

    private void persistSubjectTeacher(Subject subject, String teacherSubject) {
        SubjectTeacher subjectTeacher = new SubjectTeacher();
        subjectTeacher.subject = subject;
        subjectTeacher.teacherSubject = teacherSubject;
        subjectTeacher.persist();
    }
}
