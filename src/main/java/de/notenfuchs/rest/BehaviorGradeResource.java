package de.notenfuchs.rest;

import de.notenfuchs.domain.BehaviorGrade;
import de.notenfuchs.dto.BehaviorGradeRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Plain CRUD REST API for {@link BehaviorGrade} (Verhaltensnoten), mirroring {@link GradeResource}
 * - the day-to-day entry UI is the grid at {@code de.notenfuchs.web.BehaviorGridResource}, this is
 * for programmatic/external access to the same data.
 */
@Path("/api/behavior-grades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BehaviorGradeResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<BehaviorGrade> list(@QueryParam("studentId") Long studentId, @QueryParam("subjectId") Long subjectId) {
        String subject = currentUser.effectiveSubject();
        if (studentId != null) {
            guard.requireClassAccessStudent(studentId, subject);
        }
        if (subjectId != null) {
            guard.requireClassAccessSubject(subjectId, subject);
        }
        if (studentId != null && subjectId != null) {
            return BehaviorGrade.list("student.id = ?1 and subject.id = ?2", studentId, subjectId);
        }
        if (studentId != null) {
            return BehaviorGrade.list("student.id", studentId);
        }
        if (subjectId != null) {
            return BehaviorGrade.list("subject.id", subjectId);
        }
        return BehaviorGrade.list(
                "subject.schoolClass.id in (select ct.schoolClass.id from ClassTeacher ct where ct.teacherSubject = ?1)"
                        + " or subject.schoolClass.id in (select st.subject.schoolClass.id from SubjectTeacher st where st.teacherSubject = ?1)",
                subject);
    }

    @GET
    @Path("/{id}")
    public BehaviorGrade get(@PathParam("id") Long id) {
        return guard.requireClassAccessBehaviorGrade(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid BehaviorGradeRequest request) {
        String subject = currentUser.effectiveSubject();
        BehaviorGrade entity = new BehaviorGrade();
        entity.student = guard.requireClassAccessStudent(request.studentId, subject);
        entity.subject = guard.requireTeachesSubject(request.subjectId, subject);
        entity.value = request.value;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public BehaviorGrade update(@PathParam("id") Long id, @Valid BehaviorGradeRequest request) {
        String subject = currentUser.effectiveSubject();
        BehaviorGrade entity = guard.requireTeachesBehaviorGrade(id, subject);
        entity.student = guard.requireClassAccessStudent(request.studentId, subject);
        entity.subject = guard.requireTeachesSubject(request.subjectId, subject);
        entity.value = request.value;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        BehaviorGrade entity = guard.requireTeachesBehaviorGrade(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
