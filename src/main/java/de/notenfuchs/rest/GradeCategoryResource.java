package de.notenfuchs.rest;

import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.dto.GradeCategoryRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/grade-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GradeCategoryResource {

    @GET
    public List<GradeCategory> list(@QueryParam("subjectId") Long subjectId) {
        if (subjectId != null) {
            return GradeCategory.list("subject.id", subjectId);
        }
        return GradeCategory.listAll();
    }

    @GET
    @Path("/{id}")
    public GradeCategory get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid GradeCategoryRequest request) {
        GradeCategory entity = new GradeCategory();
        entity.subject = findSubjectOrNotFound(request.subjectId);
        entity.name = request.name;
        entity.weightPercent = request.weightPercent;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public GradeCategory update(@PathParam("id") Long id, @Valid GradeCategoryRequest request) {
        GradeCategory entity = findOrNotFound(id);
        entity.subject = findSubjectOrNotFound(request.subjectId);
        entity.name = request.name;
        entity.weightPercent = request.weightPercent;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        GradeCategory entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private GradeCategory findOrNotFound(Long id) {
        GradeCategory entity = GradeCategory.findById(id);
        if (entity == null) {
            throw new NotFoundException("GradeCategory " + id + " not found");
        }
        return entity;
    }

    private Subject findSubjectOrNotFound(Long id) {
        Subject subject = Subject.findById(id);
        if (subject == null) {
            throw new NotFoundException("Subject " + id + " not found");
        }
        return subject;
    }
}
