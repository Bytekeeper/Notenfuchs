package de.notenfuchs.rest;

import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.dto.SchoolClassRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/school-classes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchoolClassResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<SchoolClass> list() {
        return guard.listOwnedClasses(currentUser.effectiveSubject());
    }

    @GET
    @Path("/{id}")
    public SchoolClass get(@PathParam("id") Long id) {
        return guard.requireOwnedClass(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid SchoolClassRequest request) {
        SchoolClass entity = new SchoolClass();
        entity.name = request.name;
        entity.schoolYear = request.schoolYear;
        entity.ownerSubject = currentUser.effectiveSubject();
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public SchoolClass update(@PathParam("id") Long id, @Valid SchoolClassRequest request) {
        SchoolClass entity = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        entity.name = request.name;
        entity.schoolYear = request.schoolYear;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        SchoolClass entity = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
