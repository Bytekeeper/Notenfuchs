package de.notenfuchs.rest;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.dto.AssessmentRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
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

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<Assessment> list(@QueryParam("categoryId") Long categoryId) {
        String subject = currentUser.effectiveSubject();
        if (categoryId != null) {
            guard.requireTeachesCategory(categoryId, subject);
            return Assessment.list("category.id", categoryId);
        }
        return Assessment.list(
                "category.subject.id in (select st.subject.id from SubjectTeacher st where st.teacherSubject = ?1)",
                subject);
    }

    @GET
    @Path("/{id}")
    public Assessment get(@PathParam("id") Long id) {
        return guard.requireTeachesAssessment(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid AssessmentRequest request) {
        Assessment entity = new Assessment();
        entity.category = guard.requireTeachesCategory(request.categoryId, currentUser.effectiveSubject());
        entity.name = request.name;
        entity.date = request.date;
        entity.factor = request.factor != null ? request.factor : BigDecimal.ONE;
        entity.pointsBased = request.pointsBased;
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.IN_FAVOR_OF_STUDENT;
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Assessment update(@PathParam("id") Long id, @Valid AssessmentRequest request) {
        String subject = currentUser.effectiveSubject();
        Assessment entity = guard.requireTeachesAssessment(id, subject);
        entity.category = guard.requireTeachesCategory(request.categoryId, subject);
        entity.name = request.name;
        entity.date = request.date;
        entity.factor = request.factor != null ? request.factor : BigDecimal.ONE;
        entity.pointsBased = request.pointsBased;
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.IN_FAVOR_OF_STUDENT;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Assessment entity = guard.requireTeachesAssessment(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }
}
