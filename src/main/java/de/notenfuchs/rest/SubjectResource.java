package de.notenfuchs.rest;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.dto.SubjectRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/subjects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SubjectResource {

    @GET
    public List<Subject> list(@QueryParam("schoolClassId") Long schoolClassId) {
        if (schoolClassId != null) {
            return Subject.list("schoolClass.id", schoolClassId);
        }
        return Subject.listAll();
    }

    @GET
    @Path("/{id}")
    public Subject get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid SubjectRequest request) {
        Subject entity = new Subject();
        entity.schoolClass = findSchoolClassOrNotFound(request.schoolClassId);
        entity.name = request.name;
        entity.gradeScale = findGradeScaleOrNotFound(request.gradeScaleId);
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.COMMERCIAL;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Subject update(@PathParam("id") Long id, @Valid SubjectRequest request) {
        Subject entity = findOrNotFound(id);
        entity.schoolClass = findSchoolClassOrNotFound(request.schoolClassId);
        entity.name = request.name;
        entity.gradeScale = findGradeScaleOrNotFound(request.gradeScaleId);
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.COMMERCIAL;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Subject entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private Subject findOrNotFound(Long id) {
        Subject entity = Subject.findById(id);
        if (entity == null) {
            throw new NotFoundException("Subject " + id + " not found");
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

    private GradeScale findGradeScaleOrNotFound(Long id) {
        GradeScale gradeScale = GradeScale.findById(id);
        if (gradeScale == null) {
            throw new NotFoundException("GradeScale " + id + " not found");
        }
        return gradeScale;
    }
}
