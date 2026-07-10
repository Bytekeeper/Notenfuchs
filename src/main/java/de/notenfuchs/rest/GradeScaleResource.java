package de.notenfuchs.rest;

import de.notenfuchs.domain.GradeScale;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Read-only listing of available grade scales (e.g. the seeded "DE 1-6" scale).
 * New scales can be added directly in the database without any code or schema change;
 * this resource simply exposes whatever scales currently exist.
 */
@Path("/api/grade-scales")
@Produces(MediaType.APPLICATION_JSON)
public class GradeScaleResource {

    @GET
    public List<GradeScale> list() {
        return GradeScale.listAll();
    }

    @GET
    @Path("/{id}")
    public GradeScale get(@PathParam("id") Long id) {
        GradeScale entity = GradeScale.findById(id);
        if (entity == null) {
            throw new NotFoundException("GradeScale " + id + " not found");
        }
        return entity;
    }
}
