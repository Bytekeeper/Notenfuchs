package de.notenfuchs.rest;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.dto.StudentSubjectAverageResponse;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.CategoryData;
import de.notenfuchs.service.GradeData;
import de.notenfuchs.service.GradeService;
import de.notenfuchs.service.SubjectAverageResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Given a school class, computes the per-student, per-subject weighted grade average
 * (raw average + final grade) for every student x subject combination in that class,
 * by loading the relevant Panache entities, converting them to the plain DTOs expected
 * by {@link GradeService}, and delegating the actual computation to that pure service.
 */
@Path("/api/school-classes/{classId}/averages")
@Produces(MediaType.APPLICATION_JSON)
public class ClassAveragesResource {

    private final GradeService gradeService = new GradeService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<StudentSubjectAverageResponse> averages(@PathParam("classId") Long classId) {
        SchoolClass schoolClass = guard.requireOwnedClass(classId, currentUser.effectiveSubject());

        List<Student> students = Student.list("schoolClass.id = ?1 order by name", classId);
        List<Subject> subjects = Subject.list("schoolClass.id", classId);

        List<StudentSubjectAverageResponse> result = new ArrayList<>();

        for (Student student : students) {
            for (Subject subject : subjects) {
                List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);

                List<CategoryData> categoryDataList = new ArrayList<>();
                for (GradeCategory category : categories) {
                    List<Assessment> assessments = Assessment.list("category.id", category.id);

                    List<GradeData> gradeDataList = new ArrayList<>();
                    for (Assessment assessment : assessments) {
                        Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                                assessment.id, student.id).firstResult();
                        if (grade != null) {
                            gradeDataList.add(new GradeData(grade.value, assessment.factor));
                        }
                    }

                    categoryDataList.add(new CategoryData(category.weightPercent, gradeDataList));
                }

                SubjectAverageResult average = gradeService.calculateSubjectAverage(
                        categoryDataList, subject.gradeScale, subject.roundingMode);

                result.add(new StudentSubjectAverageResponse(
                        student.id, effectiveName(student),
                        subject.id, subject.name,
                        average.rawAverage(), average.finalGrade()));
            }
        }

        return result;
    }

    private static String effectiveName(Student student) {
        return student.displayName != null && !student.displayName.isBlank() ? student.displayName : student.name;
    }
}
