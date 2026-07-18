package de.notenfuchs.security;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

import java.util.List;

/**
 * The single place that enforces per-teacher data isolation. {@link SchoolClass} is the
 * ownership root ({@code ownerSubject}); every other entity is scoped by walking up to its
 * owning {@code SchoolClass} rather than carrying its own owner column.
 *
 * <p>Every REST/web endpoint that reads or writes an entity by id must resolve it through one
 * of the {@code requireOwned*} methods here instead of a raw {@code findById} - foreign or
 * unknown ids both come back as {@link NotFoundException} (404), deliberately not distinguished,
 * so a teacher can't tell "doesn't exist" apart from "isn't yours". List endpoints must go
 * through {@link #listOwnedClasses(String)} (or an equivalent scoped query) rather than
 * {@code listAll()}.
 *
 * <p>Callers obtain the subject to check against via {@link CurrentUser#effectiveSubject()}.
 */
@ApplicationScoped
public class OwnershipGuard {

    public List<SchoolClass> listOwnedClasses(String currentSubject) {
        return SchoolClass.list("ownerSubject = ?1 order by schoolYear desc, name asc", currentSubject);
    }

    public SchoolClass requireOwnedClass(Long classId, String currentSubject) {
        SchoolClass entity = SchoolClass.findById(classId);
        if (entity == null || !entity.ownerSubject.equals(currentSubject)) {
            throw new NotFoundException("SchoolClass " + classId + " not found");
        }
        return entity;
    }

    public Subject requireOwnedSubject(Long subjectId, String currentSubject) {
        Subject entity = Subject.findById(subjectId);
        if (entity == null || !isOwned(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        return entity;
    }

    public Student requireOwnedStudent(Long studentId, String currentSubject) {
        Student entity = Student.findById(studentId);
        if (entity == null || !isOwned(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        return entity;
    }

    public GradeCategory requireOwnedCategory(Long categoryId, String currentSubject) {
        GradeCategory entity = GradeCategory.findById(categoryId);
        if (entity == null || !isOwned(entity.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("GradeCategory " + categoryId + " not found");
        }
        return entity;
    }

    public Assessment requireOwnedAssessment(Long assessmentId, String currentSubject) {
        Assessment entity = Assessment.findById(assessmentId);
        if (entity == null || !isOwned(entity.category.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("Assessment " + assessmentId + " not found");
        }
        return entity;
    }

    public Grade requireOwnedGrade(Long gradeId, String currentSubject) {
        Grade entity = Grade.findById(gradeId);
        if (entity == null || !isOwned(entity.assessment.category.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("Grade " + gradeId + " not found");
        }
        return entity;
    }

    public PointsGradeBand requireOwnedPointsGradeBand(Long bandId, String currentSubject) {
        PointsGradeBand entity = PointsGradeBand.findById(bandId);
        if (entity == null || !isOwned(entity.assessment.category.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("PointsGradeBand " + bandId + " not found");
        }
        return entity;
    }

    public BehaviorGrade requireOwnedBehaviorGrade(Long behaviorGradeId, String currentSubject) {
        BehaviorGrade entity = BehaviorGrade.findById(behaviorGradeId);
        if (entity == null || !isOwned(entity.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("BehaviorGrade " + behaviorGradeId + " not found");
        }
        return entity;
    }

    private boolean isOwned(SchoolClass schoolClass, String currentSubject) {
        return schoolClass != null && schoolClass.ownerSubject.equals(currentSubject);
    }
}
