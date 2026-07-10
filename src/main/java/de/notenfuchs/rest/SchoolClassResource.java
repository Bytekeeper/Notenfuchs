package de.notenfuchs.rest;

import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.dto.SchoolClassRequest;
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

    @GET
    public List<SchoolClass> list() {
        return SchoolClass.listAll();
    }

    @GET
    @Path("/{id}")
    public SchoolClass get(@PathParam("id") Long id) {
        return findOrNotFound(id);
    }

    @POST
    @Transactional
    public Response create(@Valid SchoolClassRequest request) {
        SchoolClass entity = new SchoolClass();
        entity.name = request.name;
        entity.schoolYear = request.schoolYear;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public SchoolClass update(@PathParam("id") Long id, @Valid SchoolClassRequest request) {
        SchoolClass entity = findOrNotFound(id);
        entity.name = request.name;
        entity.schoolYear = request.schoolYear;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        SchoolClass entity = findOrNotFound(id);
        entity.delete();
        return Response.noContent().build();
    }

    private SchoolClass findOrNotFound(Long id) {
        SchoolClass entity = SchoolClass.findById(id);
        if (entity == null) {
            throw new NotFoundException("SchoolClass " + id + " not found");
        }
        return entity;
    }
}
