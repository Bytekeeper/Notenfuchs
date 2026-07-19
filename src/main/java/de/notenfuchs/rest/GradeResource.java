package de.notenfuchs.rest;

import de.notenfuchs.domain.Grade;
import de.notenfuchs.dto.GradeRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/grades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GradeResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<Grade> list(@QueryParam("studentId") Long studentId, @QueryParam("assessmentId") Long assessmentId) {
        String subject = currentUser.effectiveSubject();
        if (studentId != null) {
            guard.requireClassAccessStudent(studentId, subject);
        }
        if (assessmentId != null) {
            guard.requireTeachesAssessment(assessmentId, subject);
        }
        if (studentId != null && assessmentId != null) {
            return Grade.list("student.id = ?1 and assessment.id = ?2", studentId, assessmentId);
        }
        if (studentId != null) {
            // Class access to the student isn't enough on its own here - without an assessmentId
            // to narrow the query, this must still be filtered to subjects the caller teaches, or
            // a collaborator with access to only one subject in the class could read another
            // teacher's grades for the same student via a bare studentId query.
            return Grade.list(
                    "student.id = ?1 and assessment.category.subject.id in"
                            + " (select st.subject.id from SubjectTeacher st where st.teacherSubject = ?2)",
                    studentId, subject);
        }
        if (assessmentId != null) {
            return Grade.list("assessment.id", assessmentId);
        }
        return Grade.list(
                "assessment.category.subject.id in (select st.subject.id from SubjectTeacher st where st.teacherSubject = ?1)",
                subject);
    }

    @GET
    @Path("/{id}")
    public Grade get(@PathParam("id") Long id) {
        return guard.requireTeachesGrade(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid GradeRequest request) {
        String subject = currentUser.effectiveSubject();
        Grade entity = new Grade();
        entity.assessment = guard.requireTeachesAssessment(request.assessmentId, subject);
        entity.student = guard.requireClassAccessStudent(request.studentId, subject);
        entity.value = request.value;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Grade update(@PathParam("id") Long id, @Valid GradeRequest request) {
        String subject = currentUser.effectiveSubject();
        Grade entity = guard.requireTeachesGrade(id, subject);
        entity.assessment = guard.requireTeachesAssessment(request.assessmentId, subject);
        entity.student = guard.requireClassAccessStudent(request.studentId, subject);
        entity.value = request.value;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Grade entity = guard.requireTeachesGrade(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
