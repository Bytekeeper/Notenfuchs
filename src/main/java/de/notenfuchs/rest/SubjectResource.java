package de.notenfuchs.rest;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import de.notenfuchs.dto.SubjectRequest;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
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

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @GET
    public List<Subject> list(@QueryParam("schoolClassId") Long schoolClassId) {
        String subject = currentUser.effectiveSubject();
        if (schoolClassId != null) {
            guard.requireClassAccess(schoolClassId, subject);
            return Subject.list("schoolClass.id", schoolClassId);
        }
        return Subject.list(
                "schoolClass.id in (select ct.schoolClass.id from ClassTeacher ct where ct.teacherSubject = ?1)"
                        + " or schoolClass.id in (select st.subject.schoolClass.id from SubjectTeacher st where st.teacherSubject = ?1)",
                subject);
    }

    @GET
    @Path("/{id}")
    public Subject get(@PathParam("id") Long id) {
        return guard.requireClassAccessSubject(id, currentUser.effectiveSubject());
    }

    @POST
    @Transactional
    public Response create(@Valid SubjectRequest request) {
        String currentSubject = currentUser.effectiveSubject();
        Subject entity = new Subject();
        entity.schoolClass = guard.requireClassTeacher(request.schoolClassId, currentSubject);
        entity.name = request.name;
        entity.gradeScale = findGradeScaleOrNotFound(request.gradeScaleId);
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.IN_FAVOR_OF_STUDENT;
        entity.persist();

        SubjectTeacher teacher = new SubjectTeacher();
        teacher.subject = entity;
        teacher.teacherSubject = currentSubject;
        teacher.persist();

        return Response.status(Response.Status.CREATED).entity(entity).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Subject update(@PathParam("id") Long id, @Valid SubjectRequest request) {
        String subject = currentUser.effectiveSubject();
        Subject entity = guard.requireTeachesSubject(id, subject);
        entity.schoolClass = guard.requireClassTeacher(request.schoolClassId, subject);
        entity.name = request.name;
        entity.gradeScale = findGradeScaleOrNotFound(request.gradeScaleId);
        entity.roundingMode = request.roundingMode != null ? request.roundingMode : RoundingMode.IN_FAVOR_OF_STUDENT;
        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Subject entity = guard.requireCanDeleteSubject(id, currentUser.effectiveSubject());
        entity.delete();
        return Response.noContent().build();
    }

    private GradeScale findGradeScaleOrNotFound(Long id) {
        GradeScale gradeScale = GradeScale.findById(id);
        if (gradeScale == null) {
            throw new NotFoundException("GradeScale " + id + " not found");
        }
        return gradeScale;
    }
}
