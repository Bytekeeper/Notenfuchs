package de.notenfuchs.security;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

import java.util.List;

/**
 * The single place that enforces per-teacher data isolation and the multi-teacher access model.
 *
 * <p>Two independent axes, both keyed on a teacher's OIDC subject (see {@link CurrentUser}):
 * <ul>
 *     <li>{@link ClassTeacher} - attaches a teacher to a {@link SchoolClass} at one of two
 *     {@link ClassTeacherRole} tiers, no hierarchy between rows of the same role. {@code ADMIN}
 *     grants roster read/write, class-wide settings, managing admins and the FACHLEHRER tier
 *     itself, deleting any Subject, and a read-only class-wide grade overview. {@code FACHLEHRER}
 *     (class-level) can add a new Subject and delete/manage Subjects it personally teaches, but
 *     not administer the class - see {@link #isAdmin}/{@link #isClassTeacher}.</li>
 *     <li>{@link SubjectTeacher} - teaches a specific {@link Subject} (Fachlehrer-tier, subject
 *     level). Gates all Leistung-level access (categories, assessments, grades, subject config,
 *     renaming it) for that one subject - true regardless of any {@link ClassTeacher} role, so an
 *     admin who doesn't personally teach a subject can't see its grades either, and has no
 *     override on who else teaches it (self-service only, see {@link #requireTeachesSubject}).</li>
 * </ul>
 *
 * <p>Plain class-wide access (roster read, subject list, Verhaltensnoten) is deliberately
 * <b>derived</b>, not stored: a teacher has it if they hold a {@link ClassTeacher} row of either
 * role, or teach at least one of the class's subjects (see {@link #hasClassAccess}). This is what
 * lets a Fachlehrer share their Fach with a colleague and have that colleague land with exactly
 * the class access they need, without a separate approval step or a second row to keep in sync.
 *
 * <p>Every REST/web endpoint that reads or writes an entity by id must resolve it through one of
 * the {@code require*} methods here instead of a raw {@code findById} - foreign or unknown ids
 * both come back as {@link NotFoundException} (404), deliberately not distinguished, so a teacher
 * can't tell "doesn't exist" apart from "isn't yours". List endpoints must go through
 * {@link #listAccessibleClasses(String)} (or an equivalent scoped query) rather than
 * {@code listAll()}.
 *
 * <p>Callers obtain the subject to check against via {@link CurrentUser#effectiveSubject()}.
 */
@ApplicationScoped
public class OwnershipGuard {

    // ---- predicates ----

    /** Holds an ADMIN-tier {@link ClassTeacher} row on this class. */
    public boolean isAdmin(SchoolClass schoolClass, String teacherSubject) {
        return schoolClass != null && ClassTeacher.count(
                "schoolClass = ?1 and teacherSubject = ?2 and role = ?3",
                schoolClass, teacherSubject, ClassTeacherRole.ADMIN) > 0;
    }

    /** Holds a {@link ClassTeacher} row of either role on this class. */
    public boolean isClassTeacher(SchoolClass schoolClass, String teacherSubject) {
        return schoolClass != null
                && ClassTeacher.count("schoolClass = ?1 and teacherSubject = ?2", schoolClass, teacherSubject) > 0;
    }

    public boolean teachesSubject(Subject subject, String teacherSubject) {
        return subject != null
                && SubjectTeacher.count("subject = ?1 and teacherSubject = ?2", subject, teacherSubject) > 0;
    }

    /** Holds a {@link ClassTeacher} row of either role, or teaches at least one of its subjects. */
    public boolean hasClassAccess(SchoolClass schoolClass, String teacherSubject) {
        if (isClassTeacher(schoolClass, teacherSubject)) {
            return true;
        }
        return schoolClass != null
                && SubjectTeacher.count("subject.schoolClass = ?1 and teacherSubject = ?2", schoolClass, teacherSubject) > 0;
    }

    // ---- class-level ----

    public List<SchoolClass> listAccessibleClasses(String teacherSubject) {
        return SchoolClass.list(
                "id in (select ct.schoolClass.id from ClassTeacher ct where ct.teacherSubject = ?1)"
                        + " or id in (select st.subject.schoolClass.id from SubjectTeacher st where st.teacherSubject = ?1)"
                        + " order by schoolYear desc, name asc",
                teacherSubject);
    }

    /** Read-only class access: a {@link ClassTeacher} row of either role, or teaches at least one subject in it. */
    public SchoolClass requireClassAccess(Long classId, String currentSubject) {
        SchoolClass entity = SchoolClass.findById(classId);
        if (entity == null || !hasClassAccess(entity, currentSubject)) {
            throw new NotFoundException("SchoolClass " + classId + " not found");
        }
        return entity;
    }

    /** Class-level standing of either role: can add a Subject, but not administer the class. */
    public SchoolClass requireClassTeacher(Long classId, String currentSubject) {
        SchoolClass entity = SchoolClass.findById(classId);
        if (entity == null || !isClassTeacher(entity, currentSubject)) {
            throw new NotFoundException("SchoolClass " + classId + " not found");
        }
        return entity;
    }

    /** Class-wide mutation/admin rights: ADMIN-tier only (roster writes, class settings, admin/Fachlehrer-tier management). */
    public SchoolClass requireClassAdmin(Long classId, String currentSubject) {
        SchoolClass entity = SchoolClass.findById(classId);
        if (entity == null || !isAdmin(entity, currentSubject)) {
            throw new NotFoundException("SchoolClass " + classId + " not found");
        }
        return entity;
    }

    // ---- subject-level ----

    /** Subject metadata only (name, scale, rounding mode) - class access is enough, no Leistung visibility implied. */
    public Subject requireClassAccessSubject(Long subjectId, String currentSubject) {
        Subject entity = Subject.findById(subjectId);
        if (entity == null || !hasClassAccess(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        return entity;
    }

    /** Leistung-level access to this subject: categories, assessments, grades, subject config. */
    public Subject requireTeachesSubject(Long subjectId, String currentSubject) {
        Subject entity = Subject.findById(subjectId);
        if (entity == null || !teachesSubject(entity, currentSubject)) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        return entity;
    }

    /**
     * Deleting a Subject (destructive - cascades its categories/assessments/grades) is tighter
     * than the self-service {@link #requireTeachesSubject}: an admin can delete any Subject in
     * the class, a class-level Fachlehrer only one it personally teaches, and a subject-only
     * Fachlehrer none at all.
     */
    public Subject requireCanDeleteSubject(Long subjectId, String currentSubject) {
        Subject entity = Subject.findById(subjectId);
        if (entity == null) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        boolean allowed = isAdmin(entity.schoolClass, currentSubject)
                || (isClassTeacher(entity.schoolClass, currentSubject) && teachesSubject(entity, currentSubject));
        if (!allowed) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        return entity;
    }

    // ---- student-level ----

    /** Read-only: class access to the student's class is enough. */
    public Student requireClassAccessStudent(Long studentId, String currentSubject) {
        Student entity = Student.findById(studentId);
        if (entity == null || !hasClassAccess(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        return entity;
    }

    /** Roster mutation (add/rename/delete a student): ADMIN-tier only. */
    public Student requireRosterManageStudent(Long studentId, String currentSubject) {
        Student entity = Student.findById(studentId);
        if (entity == null || !isAdmin(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        return entity;
    }

    // ---- Leistung-level (always gated by teaching the underlying subject) ----

    public GradeCategory requireTeachesCategory(Long categoryId, String currentSubject) {
        GradeCategory entity = GradeCategory.findById(categoryId);
        if (entity == null || !teachesSubject(entity.subject, currentSubject)) {
            throw new NotFoundException("GradeCategory " + categoryId + " not found");
        }
        return entity;
    }

    public Assessment requireTeachesAssessment(Long assessmentId, String currentSubject) {
        Assessment entity = Assessment.findById(assessmentId);
        if (entity == null || !teachesSubject(entity.category.subject, currentSubject)) {
            throw new NotFoundException("Assessment " + assessmentId + " not found");
        }
        return entity;
    }

    public Grade requireTeachesGrade(Long gradeId, String currentSubject) {
        Grade entity = Grade.findById(gradeId);
        if (entity == null || !teachesSubject(entity.assessment.category.subject, currentSubject)) {
            throw new NotFoundException("Grade " + gradeId + " not found");
        }
        return entity;
    }

    public PointsGradeBand requireTeachesPointsGradeBand(Long bandId, String currentSubject) {
        PointsGradeBand entity = PointsGradeBand.findById(bandId);
        if (entity == null || !teachesSubject(entity.assessment.category.subject, currentSubject)) {
            throw new NotFoundException("PointsGradeBand " + bandId + " not found");
        }
        return entity;
    }

    // ---- Verhaltensnoten: viewable class-wide, editable only for a subject you teach ----

    public BehaviorGrade requireClassAccessBehaviorGrade(Long behaviorGradeId, String currentSubject) {
        BehaviorGrade entity = BehaviorGrade.findById(behaviorGradeId);
        if (entity == null || !hasClassAccess(entity.subject.schoolClass, currentSubject)) {
            throw new NotFoundException("BehaviorGrade " + behaviorGradeId + " not found");
        }
        return entity;
    }

    public BehaviorGrade requireTeachesBehaviorGrade(Long behaviorGradeId, String currentSubject) {
        BehaviorGrade entity = BehaviorGrade.findById(behaviorGradeId);
        if (entity == null || !teachesSubject(entity.subject, currentSubject)) {
            throw new NotFoundException("BehaviorGrade " + behaviorGradeId + " not found");
        }
        return entity;
    }

    // ---- class teacher management: adding/removing admins or class-level Fachlehrer, ADMIN-only ----

    public ClassTeacher requireClassAdminTeacher(Long classTeacherId, String currentSubject) {
        ClassTeacher entity = ClassTeacher.findById(classTeacherId);
        if (entity == null || !isAdmin(entity.schoolClass, currentSubject)) {
            throw new NotFoundException("ClassTeacher " + classTeacherId + " not found");
        }
        return entity;
    }

    // ---- subject teacher management: adding/removing Fachlehrer, self-service (any current
    // teacher of the subject, no admin override - see the Authorization section in CLAUDE.md) ----

    public SubjectTeacher requireTeachesSubjectTeacher(Long subjectTeacherId, String currentSubject) {
        SubjectTeacher entity = SubjectTeacher.findById(subjectTeacherId);
        if (entity == null || !teachesSubject(entity.subject, currentSubject)) {
            throw new NotFoundException("SubjectTeacher " + subjectTeacherId + " not found");
        }
        return entity;
    }
}
