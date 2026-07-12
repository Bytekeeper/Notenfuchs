package de.notenfuchs.rest;

import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.dto.GradeCategoryRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
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

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<GradeCategory> list(@QueryParam("subjectId") Long subjectId) {
        String subject = currentUser.effectiveSubject();
        if (subjectId != null) {
            guard.requireOwnedSubject(subjectId, subject);
            return GradeCategory.list("subject.id", subjectId);
        }
        return GradeCategory.list("subject.schoolClass.ownerSubject", subject);
    }

    @GET
    @Path("/{id}")
    public GradeCategory get(@PathParam("id") Long id) {
        return guard.requireOwnedCategory(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid GradeCategoryRequest request) {
        GradeCategory entity = new GradeCategory();
        entity.subject = guard.requireOwnedSubject(request.subjectId, currentUser.effectiveSubject());
        entity.name = request.name;
        entity.weightPercent = request.weightPercent;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public GradeCategory update(@PathParam("id") Long id, @Valid GradeCategoryRequest request) {
        String subject = currentUser.effectiveSubject();
        GradeCategory entity = guard.requireOwnedCategory(id, subject);
        entity.subject = guard.requireOwnedSubject(request.subjectId, subject);
        entity.name = request.name;
        entity.weightPercent = request.weightPercent;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        GradeCategory entity = guard.requireOwnedCategory(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
