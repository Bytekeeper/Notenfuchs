package de.notenfuchs.rest;

import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.dto.StudentRequest;
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

    @GET
    public List<Student> list(@QueryParam("schoolClassId") Long schoolClassId) {
        if (schoolClassId != null) {
            return Student.list("schoolClass.id", schoolClassId);
        }
        return Student.listAll();
    }

    @GET
    @Path("/{id}")
    public Student get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid StudentRequest request) {
        SchoolClass schoolClass = findSchoolClassOrNotFound(request.schoolClassId);
        Student entity = new Student();
        entity.schoolClass = schoolClass;
        entity.name = request.name;
        entity.displayName = request.displayName;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Student update(@PathParam("id") Long id, @Valid StudentRequest request) {
        Student entity = findOrNotFound(id);
        entity.schoolClass = findSchoolClassOrNotFound(request.schoolClassId);
        entity.name = request.name;
        entity.displayName = request.displayName;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Student entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private Student findOrNotFound(Long id) {
        Student entity = Student.findById(id);
        if (entity == null) {
            throw new NotFoundException("Student " + id + " not found");
        }
        return entity;
    }

    private SchoolClass findSchoolClassOrNotFound(Long id) {
        SchoolClass schoolClass = SchoolClass.findById(id);
        if (schoolClass == null) {
            throw new NotFoundException("SchoolClass " + id + " not found");
        }
        return schoolClass;
    }
}
