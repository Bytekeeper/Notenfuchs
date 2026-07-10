package de.notenfuchs.web;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Server-rendered HTML pages for managing school classes, and (within a class) its
 * students and subjects. Complements the JSON {@code /api/*} resources - this resource
 * renders Qute templates and HTMX fragments instead.
 */
@Path("/classes")
public class ClassUiResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    @Location("ClassPage/list.html")
    Template listTemplate;

    @Inject
    @Location("ClassPage/detail.html")
    Template detailTemplate;

    @Inject
    @Location("fragments/classList.html")
    Template classListFragment;

    @Inject
    @Location("fragments/subjectList.html")
    Template subjectListFragment;

    @Inject
    @Location("fragments/studentList.html")
    Template studentListFragment;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        return withUser(listTemplate.data("classes", SchoolClass.listAll()));
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        List<Subject> subjects = Subject.list("schoolClass.id", id);
        List<Student> students = Student.list("schoolClass.id", id);
        List<GradeScale> gradeScales = GradeScale.listAll();
        return withUser(detailTemplate
                .data("schoolClass", schoolClass)
                .data("subjects", subjects)
                .data("students", students)
                .data("gradeScales", gradeScales));
    }

    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance create(@FormParam("name") String name, @FormParam("schoolYear") String schoolYear) {
        SchoolClass entity = new SchoolClass();
        entity.name = name;
        entity.schoolYear = schoolYear;
        entity.persist();
        return classListFragment.data("classes", SchoolClass.listAll());
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance delete(@PathParam("id") Long id) {
        SchoolClass entity = findClassOrNotFound(id);
        entity.delete();
        return classListFragment.data("classes", SchoolClass.listAll());
    }

    @PATCH
    @Path("/{id}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance rename(@PathParam("id") Long id, @FormParam("name") String name) {
        SchoolClass entity = findClassOrNotFound(id);
        if (name != null && !name.isBlank()) {
            entity.name = name;
        }
        return classListFragment.data("classes", SchoolClass.listAll());
    }

    @POST
    @Path("/{id}/students")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addStudent(@PathParam("id") Long id,
                                        @FormParam("name") String name,
                                        @FormParam("displayName") String displayName) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        Student student = new Student();
        student.schoolClass = schoolClass;
        student.name = name;
        student.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
        student.persist();
        List<Student> students = Student.list("schoolClass.id", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    @DELETE
    @Path("/{id}/students/{studentId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        Student student = Student.findById(studentId);
        if (student != null) {
            student.delete();
        }
        List<Student> students = Student.list("schoolClass.id", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    @PATCH
    @Path("/{id}/students/{studentId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId,
                                           @FormParam("name") String name) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        Student student = Student.findById(studentId);
        if (student == null) {
            throw new NotFoundException("Student " + studentId + " not found");
        }
        if (name != null && !name.isBlank()) {
            student.name = name;
        }
        List<Student> students = Student.list("schoolClass.id", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    @POST
    @Path("/{id}/subjects")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addSubject(@PathParam("id") Long id,
                                        @FormParam("name") String name,
                                        @FormParam("gradeScaleId") Long gradeScaleId,
                                        @FormParam("roundingMode") String roundingMode) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        GradeScale gradeScale = GradeScale.findById(gradeScaleId);
        if (gradeScale == null) {
            throw new NotFoundException("GradeScale " + gradeScaleId + " not found");
        }
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = name;
        subject.gradeScale = gradeScale;
        subject.roundingMode = (roundingMode == null || roundingMode.isBlank())
                ? RoundingMode.COMMERCIAL
                : RoundingMode.valueOf(roundingMode);
        subject.persist();
        List<Subject> subjects = subjectsWithGradeScale(id);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects);
    }

    @DELETE
    @Path("/{id}/subjects/{subjectId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteSubject(@PathParam("id") Long id, @PathParam("subjectId") Long subjectId) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        Subject subject = Subject.findById(subjectId);
        if (subject != null) {
            subject.delete();
        }
        List<Subject> subjects = subjectsWithGradeScale(id);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects);
    }

    @PATCH
    @Path("/{id}/subjects/{subjectId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameSubject(@PathParam("id") Long id, @PathParam("subjectId") Long subjectId,
                                           @FormParam("name") String name) {
        SchoolClass schoolClass = findClassOrNotFound(id);
        Subject subject = Subject.findById(subjectId);
        if (subject == null) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        if (name != null && !name.isBlank()) {
            subject.name = name;
        }
        List<Subject> subjects = subjectsWithGradeScale(id);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects);
    }

    /**
     * Fetch-joins {@code gradeScale} so {@code fragments/subjectList.html} can read
     * {@code s.gradeScale.name} after this (transaction-scoped) method returns - by then
     * the session backing the lazy association is already closed, and only eagerly-fetched
     * data survives into the async Qute render.
     */
    private List<Subject> subjectsWithGradeScale(Long schoolClassId) {
        return Subject.find("from Subject s join fetch s.gradeScale where s.schoolClass.id = ?1", schoolClassId).list();
    }

    private SchoolClass findClassOrNotFound(Long id) {
        SchoolClass entity = SchoolClass.findById(id);
        if (entity == null) {
            throw new NotFoundException("SchoolClass " + id + " not found");
        }
        return entity;
    }

    private TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", currentUser.isAuthenticated())
                .data("currentUserDisplayName", currentUser.displayName().orElse(""));
    }
}
