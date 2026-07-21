package de.notenfuchs.rest;

import de.notenfuchs.domain.Student;
import de.notenfuchs.dto.StudentRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/students")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StudentResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<Student> list(@QueryParam("schoolClassId") Long schoolClassId) {
        String subject = currentUser.effectiveSubject();
        if (schoolClassId != null) {
            guard.requireClassAccess(schoolClassId, subject);
            return Student.list("schoolClass.id = ?1 order by name", schoolClassId);
        }
        return Student.list(
                "(schoolClass.id in (select ct.schoolClass.id from ClassTeacher ct where ct.teacherSubject = ?1)"
                        + " or schoolClass.id in (select st.subject.schoolClass.id from SubjectTeacher st where st.teacherSubject = ?1))"
                        + " order by name",
                subject);
    }

    @GET
    @Path("/{id}")
    public Student get(@PathParam("id") Long id) {
        return guard.requireClassAccessStudent(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid StudentRequest request) {
        Student entity = new Student();
        entity.schoolClass = guard.requireClassAdmin(request.schoolClassId, currentUser.effectiveSubject());
        entity.name = request.name;
        entity.displayName = request.displayName;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Student update(@PathParam("id") Long id, @Valid StudentRequest request) {
        String subject = currentUser.effectiveSubject();
        Student entity = guard.requireRosterManageStudent(id, subject);
        entity.schoolClass = guard.requireClassAdmin(request.schoolClassId, subject);
        entity.name = request.name;
        entity.displayName = request.displayName;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Student entity = guard.requireRosterManageStudent(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
