package de.notenfuchs.rest;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.Student;
import de.notenfuchs.dto.GradeRequest;
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

    @GET
    public List<Grade> list(@QueryParam("studentId") Long studentId, @QueryParam("assessmentId") Long assessmentId) {
        if (studentId != null && assessmentId != null) {
            return Grade.list("student.id = ?1 and assessment.id = ?2", studentId, assessmentId);
        }
        if (studentId != null) {
            return Grade.list("student.id", studentId);
        }
        if (assessmentId != null) {
            return Grade.list("assessment.id", assessmentId);
        }
        return Grade.listAll();
    }

    @GET
    @Path("/{id}")
    public Grade get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid GradeRequest request) {
        Grade entity = new Grade();
        entity.assessment = findAssessmentOrNotFound(request.assessmentId);
        entity.student = findStudentOrNotFound(request.studentId);
        entity.value = request.value;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Grade update(@PathParam("id") Long id, @Valid GradeRequest request) {
        Grade entity = findOrNotFound(id);
        entity.assessment = findAssessmentOrNotFound(request.assessmentId);
        entity.student = findStudentOrNotFound(request.studentId);
        entity.value = request.value;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Grade entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private Grade findOrNotFound(Long id) {
        Grade entity = Grade.findById(id);
        if (entity == null) {
            throw new NotFoundException("Grade " + id + " not found");
        }
        return entity;
    }

    private Assessment findAssessmentOrNotFound(Long id) {
        Assessment assessment = Assessment.findById(id);
        if (assessment == null) {
            throw new NotFoundException("Assessment " + id + " not found");
        }
        return assessment;
    }

    private Student findStudentOrNotFound(Long id) {
        Student student = Student.findById(id);
        if (student == null) {
            throw new NotFoundException("Student " + id + " not found");
        }
        return student;
    }
}
