package de.notenfuchs.security;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-tenant isolation coverage for {@link OwnershipGuard}, the central point every REST/web
 * resource routes through instead of raw {@code findById}/{@code listAll}. Needs a real
 * Postgres (Testcontainers Dev Services, see application.properties), so this is a Failsafe IT
 * (./mvnw verify), not a plain unit test - same reasoning as the browser ITs in
 * {@code src/test/java/de/notenfuchs/e2e}.
 *
 * <p>Each test seeds its own two {@code SchoolClass} rows directly with distinct
 * {@code ownerSubject} values ("teacherA" / "teacherB") rather than needing two real OIDC
 * logins, then asserts the guard's list/require methods enforce isolation between them.
 * {@code @TestTransaction} rolls each test back afterwards, so tests don't need unique names to
 * stay independent of each other or of pre-existing data.
 */
@QuarkusTest
class OwnershipGuardIT {

    @Inject
    OwnershipGuard guard;

    @Test
    @TestTransaction
    void listOwnedClasses_excludesClassesOwnedByAnotherTeacher() {
        SchoolClass a = persistClass("teacherA");
        SchoolClass b = persistClass("teacherB");

        List<SchoolClass> ownedByA = guard.listOwnedClasses("teacherA");

        assertTrue(ownedByA.stream().anyMatch(c -> c.id.equals(a.id)));
        assertTrue(ownedByA.stream().noneMatch(c -> c.id.equals(b.id)));
    }

    @Test
    @TestTransaction
    void requireOwnedClass_ownClass_returnsIt() {
        SchoolClass a = persistClass("teacherA");

        SchoolClass found = guard.requireOwnedClass(a.id, "teacherA");

        assertEquals(a.id, found.id);
    }

    @Test
    @TestTransaction
    void requireOwnedClass_foreignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");

        assertThrows(NotFoundException.class, () -> guard.requireOwnedClass(b.id, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireOwnedClass_unknownId_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> guard.requireOwnedClass(-1L, "teacherA"));
    }

    @Test
    @TestTransaction
    void requireOwnedSubject_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b);

        assertThrows(NotFoundException.class, () -> guard.requireOwnedSubject(subject.id, "teacherA"));
        assertEquals(subject.id, guard.requireOwnedSubject(subject.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireOwnedStudent_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Student student = new Student();
        student.schoolClass = b;
        student.name = "Fremder Schueler";
        student.persist();

        assertThrows(NotFoundException.class, () -> guard.requireOwnedStudent(student.id, "teacherA"));
        assertEquals(student.id, guard.requireOwnedStudent(student.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireOwnedCategoryAndAssessment_belongingToForeignClass_throwNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b);
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

        assertThrows(NotFoundException.class, () -> guard.requireOwnedCategory(category.id, "teacherA"));
        assertThrows(NotFoundException.class, () -> guard.requireOwnedAssessment(assessment.id, "teacherA"));
        assertEquals(category.id, guard.requireOwnedCategory(category.id, "teacherB").id);
        assertEquals(assessment.id, guard.requireOwnedAssessment(assessment.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireOwnedPointsGradeBand_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b);
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
        band.minPoints = new BigDecimal("50");
        band.gradeValue = new BigDecimal("4");
        band.persist();

        assertThrows(NotFoundException.class, () -> guard.requireOwnedPointsGradeBand(band.id, "teacherA"));
        assertEquals(band.id, guard.requireOwnedPointsGradeBand(band.id, "teacherB").id);
    }

    @Test
    @TestTransaction
    void requireOwnedBehaviorGrade_belongingToForeignClass_throwsNotFound() {
        SchoolClass b = persistClass("teacherB");
        Subject subject = persistSubject(b);
        Student student = new Student();
        student.schoolClass = b;
        student.name = "Fremder Schueler";
        student.persist();
        BehaviorGrade behaviorGrade = new BehaviorGrade();
        behaviorGrade.student = student;
        behaviorGrade.subject = subject;
        behaviorGrade.value = new BigDecimal("2");
        behaviorGrade.persist();

        assertThrows(NotFoundException.class, () -> guard.requireOwnedBehaviorGrade(behaviorGrade.id, "teacherA"));
        assertEquals(behaviorGrade.id, guard.requireOwnedBehaviorGrade(behaviorGrade.id, "teacherB").id);
    }

    private SchoolClass persistClass(String owner) {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.name = "Ownership-Test-Klasse-" + owner + "-" + System.nanoTime();
        schoolClass.schoolYear = "2025/26";
        schoolClass.ownerSubject = owner;
        schoolClass.persist();
        return schoolClass;
    }

    private Subject persistSubject(SchoolClass schoolClass) {
        GradeScale gradeScale = GradeScale.find("name", "DE 1-6").firstResult();
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = "Ownership-Test-Fach-" + System.nanoTime();
        subject.gradeScale = gradeScale;
        subject.roundingMode = RoundingMode.COMMERCIAL;
        subject.persist();
        return subject;
    }
}
