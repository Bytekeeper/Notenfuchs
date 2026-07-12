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
            guard.requireOwnedStudent(studentId, subject);
        }
        if (assessmentId != null) {
            guard.requireOwnedAssessment(assessmentId, subject);
        }
        if (studentId != null && assessmentId != null) {
            return Grade.list("student.id = ?1 and assessment.id = ?2", studentId, assessmentId);
        }
        if (studentId != null) {
            return Grade.list("student.id", studentId);
        }
        if (assessmentId != null) {
            return Grade.list("assessment.id", assessmentId);
        }
        return Grade.list("assessment.category.subject.schoolClass.ownerSubject", subject);
    }

    @GET
    @Path("/{id}")
    public Grade get(@PathParam("id") Long id) {
        return guard.requireOwnedGrade(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid GradeRequest request) {
        String subject = currentUser.effectiveSubject();
        Grade entity = new Grade();
        entity.assessment = guard.requireOwnedAssessment(request.assessmentId, subject);
        entity.student = guard.requireOwnedStudent(request.studentId, subject);
        entity.value = request.value;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Grade update(@PathParam("id") Long id, @Valid GradeRequest request) {
        String subject = currentUser.effectiveSubject();
        Grade entity = guard.requireOwnedGrade(id, subject);
        entity.assessment = guard.requireOwnedAssessment(request.assessmentId, subject);
        entity.student = guard.requireOwnedStudent(request.studentId, subject);
        entity.value = request.value;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Grade entity = guard.requireOwnedGrade(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
