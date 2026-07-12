package de.notenfuchs.web;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.CsvRosterService;
import de.notenfuchs.service.RosterParseResult;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-rendered HTML pages for managing school classes, and (within a class) its
 * students and subjects. Complements the JSON {@code /api/*} resources - this resource
 * renders Qute templates and HTMX fragments instead.
 */
@Path("/classes")
public class ClassUiResource {

    private final CsvRosterService csvRosterService = new CsvRosterService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    @Location("ClassPage/list.html")
    Template listTemplate;

    @Inject
    @Location("ClassPage/detail.html")
    Template detailTemplate;

    @Inject
    @Location("ClassPage/rosterImportPreview.html")
    Template rosterImportPreviewTemplate;

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
        return withUser(listTemplate.data("classes", guard.listOwnedClasses(currentUser.effectiveSubject())));
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id,
                                    @QueryParam("rosterImportResult") String rosterImportResult) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        List<Subject> subjects = Subject.list("schoolClass.id", id);
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        List<GradeScale> gradeScales = GradeScale.listAll();
        return withUser(detailTemplate
                .data("schoolClass", schoolClass)
                .data("subjects", subjects)
                .data("students", students)
                .data("gradeScales", gradeScales)
                .data("rosterImportResult", rosterImportResult));
    }

    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance create(@FormParam("name") String name, @FormParam("schoolYear") String schoolYear) {
        String subject = currentUser.effectiveSubject();
        SchoolClass entity = new SchoolClass();
        entity.name = name;
        entity.schoolYear = schoolYear;
        entity.ownerSubject = subject;
        entity.persist();
        return classListFragment.data("classes", guard.listOwnedClasses(subject));
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance delete(@PathParam("id") Long id) {
        String subject = currentUser.effectiveSubject();
        SchoolClass entity = guard.requireOwnedClass(id, subject);
        entity.delete();
        return classListFragment.data("classes", guard.listOwnedClasses(subject));
    }

    @PATCH
    @Path("/{id}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance rename(@PathParam("id") Long id, @FormParam("name") String name) {
        String subject = currentUser.effectiveSubject();
        SchoolClass entity = guard.requireOwnedClass(id, subject);
        if (name != null && !name.isBlank()) {
            entity.name = name;
        }
        return classListFragment.data("classes", guard.listOwnedClasses(subject));
    }

    @POST
    @Path("/{id}/students")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addStudent(@PathParam("id") Long id,
                                        @FormParam("name") String name,
                                        @FormParam("displayName") String displayName) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        Student student = new Student();
        student.schoolClass = schoolClass;
        student.name = name;
        student.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
        student.persist();
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    @DELETE
    @Path("/{id}/students/{studentId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId) {
        String subject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireOwnedClass(id, subject);
        Student student = guard.requireOwnedStudent(studentId, subject);
        student.delete();
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    @PATCH
    @Path("/{id}/students/{studentId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId,
                                           @FormParam("name") String name) {
        String subject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireOwnedClass(id, subject);
        Student student = guard.requireOwnedStudent(studentId, subject);
        if (name != null && !name.isBlank()) {
            student.name = name;
        }
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students);
    }

    /**
     * Downloads this class's roster (its students' names) as CSV - UTF-8 with a BOM and a
     * semicolon delimiter (see {@link CsvRosterService}), so German Excel opens umlauts
     * correctly without a manual encoding prompt.
     */
    @GET
    @Path("/{id}/roster/export")
    @Produces("text/csv; charset=UTF-8")
    public Response exportRoster(@PathParam("id") Long id) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        List<String> names = students.stream().map(s -> s.name).toList();
        byte[] csv = csvRosterService.format(names);

        String plainFilename = "klasse-" + schoolClass.name + "-schueler.csv";
        String asciiFilename = "klasse-" + sanitizeFilename(schoolClass.name) + "-schueler.csv";
        String utf8Filename = URLEncoder.encode(plainFilename, StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"" + asciiFilename
                        + "\"; filename*=UTF-8''" + utf8Filename)
                .build();
    }

    /**
     * Step 1 of roster import: parses the uploaded CSV and renders a preview - NOT a direct
     * import - marking each row NEW or DUPLICATE against this class's existing students. Kept
     * stateless: the confirm form on the preview page carries the parsed names back as hidden
     * inputs (see {@code ClassPage/rosterImportPreview.html}), so {@link #confirmRosterImport}
     * doesn't depend on anything server-side surviving between the two requests.
     */
    @POST
    @Path("/{id}/roster/import/preview")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public TemplateInstance previewRosterImport(@PathParam("id") Long id, @RestForm("file") FileUpload file) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        if (file == null) {
            throw new BadRequestException("Keine Datei hochgeladen");
        }
        RosterParseResult parsed;
        try {
            parsed = csvRosterService.parseDetailed(readUploadedFile(file));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }

        Set<String> seenNames = existingStudentNames(id);
        List<RosterPreviewRow> rows = new ArrayList<>();
        int newCount = 0;
        int duplicateCount = 0;
        for (String name : parsed.names()) {
            boolean duplicate = !seenNames.add(name);
            rows.add(new RosterPreviewRow(name, duplicate));
            if (duplicate) {
                duplicateCount++;
            } else {
                newCount++;
            }
        }

        return withUser(rosterImportPreviewTemplate
                .data("schoolClass", schoolClass)
                .data("rows", rows)
                .data("newCount", newCount)
                .data("duplicateCount", duplicateCount)
                .data("blankLinesSkipped", parsed.blankLinesSkipped()));
    }

    /**
     * Step 2 of roster import: creates a {@link Student} per submitted name, skipping names
     * that already exist in the class (exact match) - including duplicates within the
     * submission itself, since by the time a later same-name row would be created, the
     * earlier one already "exists in the class".
     */
    @POST
    @Path("/{id}/roster/import")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response confirmRosterImport(@PathParam("id") Long id, @FormParam("names") List<String> submittedNames) {
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
        Set<String> seenNames = existingStudentNames(id);

        int created = 0;
        int skipped = 0;
        if (submittedNames != null) {
            for (String rawName : submittedNames) {
                String name = rawName == null ? "" : rawName.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!seenNames.add(name)) {
                    skipped++;
                    continue;
                }
                Student student = new Student();
                student.schoolClass = schoolClass;
                student.name = name;
                student.persist();
                created++;
            }
        }

        String message = created + " Schüler angelegt, " + skipped + " übersprungen (bereits vorhanden)";
        URI redirect = URI.create("/classes/" + id + "?rosterImportResult="
                + URLEncoder.encode(message, StandardCharsets.UTF_8));
        return Response.seeOther(redirect).build();
    }

    private Set<String> existingStudentNames(Long schoolClassId) {
        List<Student> students = Student.list("schoolClass.id", schoolClassId);
        return new HashSet<>(students.stream().map(s -> s.name).toList());
    }

    private byte[] readUploadedFile(FileUpload file) {
        try {
            return Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentUser.effectiveSubject());
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
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentSubject);
        Subject subject = guard.requireOwnedSubject(subjectId, currentSubject);
        subject.delete();
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
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireOwnedClass(id, currentSubject);
        Subject subject = guard.requireOwnedSubject(subjectId, currentSubject);
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

    private TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", currentUser.isAuthenticated())
                .data("currentUserDisplayName", currentUser.displayName().orElse(""));
    }

    /** Strips German umlauts/eszett and any other non-ASCII-safe character for the plain filename attribute. */
    private static String sanitizeFilename(String raw) {
        String transliterated = raw
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        return transliterated.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    /** One row of the roster import preview - see {@code ClassPage/rosterImportPreview.html}. */
    public record RosterPreviewRow(String name, boolean duplicate) {
    }
}
