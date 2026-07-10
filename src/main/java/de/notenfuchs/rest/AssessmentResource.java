package de.notenfuchs.rest;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.dto.AssessmentRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;

@Path("/api/assessments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssessmentResource {

    @GET
    public List<Assessment> list(@QueryParam("categoryId") Long categoryId) {
        if (categoryId != null) {
            return Assessment.list("category.id", categoryId);
        }
        return Assessment.listAll();
    }

    @GET
    @Path("/{id}")
    public Assessment get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid AssessmentRequest request) {
        Assessment entity = new Assessment();
        entity.category = findCategoryOrNotFound(request.categoryId);
        entity.name = request.name;
        entity.date = request.date;
        entity.factor = request.factor != null ? request.factor : BigDecimal.ONE;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Assessment update(@PathParam("id") Long id, @Valid AssessmentRequest request) {
        Assessment entity = findOrNotFound(id);
        entity.category = findCategoryOrNotFound(request.categoryId);
        entity.name = request.name;
        entity.date = request.date;
        entity.factor = request.factor != null ? request.factor : BigDecimal.ONE;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Assessment entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private Assessment findOrNotFound(Long id) {
        Assessment entity = Assessment.findById(id);
        if (entity == null) {
            throw new NotFoundException("Assessment " + id + " not found");
        }
        return entity;
    }

    private GradeCategory findCategoryOrNotFound(Long id) {
        GradeCategory category = GradeCategory.findById(id);
        if (category == null) {
            throw new NotFoundException("GradeCategory " + id + " not found");
        }
        return category;
    }
}
