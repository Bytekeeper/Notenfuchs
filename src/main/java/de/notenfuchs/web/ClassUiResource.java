package de.notenfuchs.web;

import de.notenfuchs.domain.ClassTeacher;
import de.notenfuchs.domain.ClassTeacherRole;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.HalfYearGradeDisplay;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import de.notenfuchs.domain.Teacher;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.CsvRosterService;
import de.notenfuchs.service.RosterParseResult;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
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
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Inject
    @Location("fragments/halfYearCutoff.html")
    Template halfYearCutoffFragment;

    @Inject
    @Location("fragments/halfYearGradeDisplay.html")
    Template halfYearGradeDisplayFragment;

    @Inject
    @Location("fragments/classTeachers.html")
    Template classTeachersFragment;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        String subject = currentUser.effectiveSubject();
        return currentUser.withUser(listTemplate
                .data("yearGroups", groupByYear(guard.listAccessibleClasses(subject)))
                .data("adminClassIds", adminClassIds(subject)));
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id,
                                    @QueryParam("rosterImportResult") String rosterImportResult) {
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassAccess(id, currentSubject);
        List<Subject> subjects = Subject.list("schoolClass.id", id);
        attachTeacherLabels(subjects);
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        List<GradeScale> gradeScales = GradeScale.listAll();
        return currentUser.withUser(detailTemplate
                .data("schoolClass", schoolClass)
                .data("predecessorClass", classWithPredecessorFetched(id).predecessorClass)
                .data("subjects", subjects)
                .data("taughtSubjectIds", taughtSubjectIds(id, currentSubject))
                .data("students", students)
                .data("gradeScales", gradeScales)
                .data("rosterImportResult", rosterImportResult)
                .data("classTeachers", classTeachersWithResolvedLabels(id))
                .data("availableTeachers", availableTeachers(id))
                .data("transitiveTeachers", transitiveTeachers(id))
                .data("currentUserSubject", currentSubject)
                .data("isClassAdmin", guard.isAdmin(schoolClass, currentSubject))
                .data("isClassTeacher", guard.isClassTeacher(schoolClass, currentSubject)));
    }

    /**
     * Copies a class into a new school year (e.g. 8b -> 9b): a new {@link SchoolClass} owned
     * by the current teacher, with the source's Subjects (+ their GradeCategories) and
     * Students copied over as an editable starting point. Assessments and Grades are
     * deliberately NOT copied - fresh start for the new year. The source class is left
     * completely untouched and stays a normal, editable class (no locking/archiving anywhere -
     * see ROADMAP.md's design principle).
     */
    @POST
    @Path("/{id}/duplicate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response duplicate(@PathParam("id") Long id,
                               @FormParam("name") String name,
                               @FormParam("schoolYear") String schoolYear) {
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass source = guard.requireClassAdmin(id, currentSubject);

        SchoolClass copy = new SchoolClass();
        copy.name = name;
        copy.schoolYear = schoolYear;
        copy.predecessorClass = source;
        copy.persist();

        ClassTeacher copyOwner = new ClassTeacher();
        copyOwner.schoolClass = copy;
        copyOwner.teacherSubject = currentSubject;
        copyOwner.role = ClassTeacherRole.ADMIN;
        copyOwner.persist();

        List<Subject> sourceSubjects = Subject.list("schoolClass.id", id);
        for (Subject sourceSubject : sourceSubjects) {
            Subject subjectCopy = new Subject();
            subjectCopy.schoolClass = copy;
            subjectCopy.name = sourceSubject.name;
            subjectCopy.gradeScale = sourceSubject.gradeScale;
            subjectCopy.roundingMode = sourceSubject.roundingMode;
            subjectCopy.persist();

            SubjectTeacher subjectCopyTeacher = new SubjectTeacher();
            subjectCopyTeacher.subject = subjectCopy;
            subjectCopyTeacher.teacherSubject = currentSubject;
            subjectCopyTeacher.persist();

            List<GradeCategory> sourceCategories = GradeCategory.list("subject.id", sourceSubject.id);
            for (GradeCategory sourceCategory : sourceCategories) {
                GradeCategory categoryCopy = new GradeCategory();
                categoryCopy.subject = subjectCopy;
                categoryCopy.name = sourceCategory.name;
                categoryCopy.weightPercent = sourceCategory.weightPercent;
                categoryCopy.persist();
            }
        }

        List<Student> sourceStudents = Student.list("schoolClass.id", id);
        for (Student sourceStudent : sourceStudents) {
            Student studentCopy = new Student();
            studentCopy.schoolClass = copy;
            studentCopy.name = sourceStudent.name;
            studentCopy.displayName = sourceStudent.displayName;
            studentCopy.persist();
        }

        return Response.seeOther(URI.create("/classes/" + copy.id)).build();
    }

    /**
     * Fetch-joins {@code predecessorClass} for the same reason {@link #subjectsWithGradeScale}
     * does for {@code gradeScale}: the async Qute render happens after this method's
     * persistence context is gone, so only an eagerly-fetched association survives into it.
     */
    private SchoolClass classWithPredecessorFetched(Long id) {
        return SchoolClass.find("from SchoolClass c left join fetch c.predecessorClass where c.id = ?1", id)
                .firstResult();
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
        entity.persist();

        ClassTeacher owner = new ClassTeacher();
        owner.schoolClass = entity;
        owner.teacherSubject = subject;
        owner.role = ClassTeacherRole.ADMIN;
        owner.persist();

        return classListFragment
                .data("yearGroups", groupByYear(guard.listAccessibleClasses(subject)))
                .data("adminClassIds", adminClassIds(subject));
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance delete(@PathParam("id") Long id) {
        String subject = currentUser.effectiveSubject();
        SchoolClass entity = guard.requireClassAdmin(id, subject);
        entity.delete();
        return classListFragment
                .data("yearGroups", groupByYear(guard.listAccessibleClasses(subject)))
                .data("adminClassIds", adminClassIds(subject));
    }

    @PATCH
    @Path("/{id}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance rename(@PathParam("id") Long id, @FormParam("name") String name,
                                    @FormParam("schoolYear") String schoolYear) {
        String subject = currentUser.effectiveSubject();
        SchoolClass entity = guard.requireClassAdmin(id, subject);
        if (name != null && !name.isBlank()) {
            entity.name = name;
        }
        if (schoolYear != null && !schoolYear.isBlank()) {
            entity.schoolYear = schoolYear;
        }
        return classListFragment
                .data("yearGroups", groupByYear(guard.listAccessibleClasses(subject)))
                .data("adminClassIds", adminClassIds(subject));
    }

    /**
     * Attaches a teacher to the class at either {@link ClassTeacherRole} tier - the current admin
     * picks one from the {@link Teacher} directory (see {@link #availableTeachers}), since there's
     * no other way to identify a colleague (no email lookup, no invite links - see the
     * class-access-UI plan). Admin-only: granting either tier is a class-structural decision, not
     * self-service like {@code SubjectTeacher} sharing on a single Fach is. Not named
     * {@code addTeacher}: this codebase has three different "teacher" concepts ({@link Teacher}
     * the directory, {@link ClassTeacher} this row, {@link SubjectTeacher} the per-subject row).
     */
    @POST
    @Path("/{id}/teachers")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addClassTeacher(@PathParam("id") Long id,
                                             @FormParam("teacherSubject") String teacherSubject,
                                             @FormParam("role") String role) {
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
        Teacher knownTeacher = Teacher.find("subject", teacherSubject).firstResult();
        if (knownTeacher == null) {
            throw new BadRequestException("Unbekannte Lehrkraft");
        }
        if (ClassTeacher.count("schoolClass.id = ?1 and teacherSubject = ?2", id, teacherSubject) > 0) {
            throw new BadRequestException("Diese Lehrkraft hat bereits Zugriff auf die Klasse");
        }
        ClassTeacher classTeacher = new ClassTeacher();
        classTeacher.schoolClass = schoolClass;
        classTeacher.teacherSubject = teacherSubject;
        classTeacher.role = parseRole(role);
        try {
            // Flushed immediately (not deferred to commit) so a double-submitted request that raced
            // past the count check above surfaces here as the same clean 400, not an uncaught
            // constraint-violation 500 at transaction commit.
            classTeacher.persistAndFlush();
        } catch (PersistenceException e) {
            throw new BadRequestException("Diese Lehrkraft hat bereits Zugriff auf die Klasse");
        }
        return classTeachersResponse(schoolClass);
    }

    /** Blank defaults to the lower-privilege {@code FACHLEHRER}; any other unrecognized value is a 400, not a 500. */
    private ClassTeacherRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return ClassTeacherRole.FACHLEHRER;
        }
        try {
            return ClassTeacherRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unbekannte Rolle: " + role);
        }
    }

    /**
     * Removes a {@link ClassTeacher} row. Guarded so a class always keeps at least one
     * {@code ADMIN}-tier row - the primary defense is {@code fragments/classTeachers.html} not
     * rendering "Entfernen" at all next to the last remaining admin ({@link ClassTeacher#canRemove});
     * this is defense-in-depth for direct API misuse or races. Removing a {@code FACHLEHRER}-tier
     * row is never blocked by this guardrail - a class can have zero of those at any time. Any row
     * (including the acting teacher's own) can be removed. Self-removal is special-cased: this
     * ADMIN-only teacher-management fragment is never valid to re-render for the actor once they've
     * lost their own {@code ClassTeacher} row, so an {@code HX-Redirect} is always sent instead of
     * returning the fragment - but same as {@link SubjectUiResource#removeSubjectTeacher}, the
     * target is computed from {@link OwnershipGuard#hasClassAccess}, checked right after the delete
     * (same transaction), since the actor may still teach a Subject in this class.
     */
    @DELETE
    @Path("/{id}/teachers/{teacherId}")
    @Transactional
    public Response removeClassTeacher(@PathParam("id") Long id, @PathParam("teacherId") Long teacherId) {
        String subject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassAdmin(id, subject);
        ClassTeacher classTeacher = guard.requireClassAdminTeacher(teacherId, subject);
        if (!classTeacher.schoolClass.id.equals(id)) {
            throw new NotFoundException("ClassTeacher " + teacherId + " not found");
        }
        if (classTeacher.role == ClassTeacherRole.ADMIN) {
            // Locks every ADMIN-tier row of this class for the rest of the transaction, so a second,
            // concurrent self/peer removal request for the same class blocks here until this one
            // commits or rolls back - closing the TOCTOU race a plain count-then-delete would have.
            long adminCount = ClassTeacher
                    .find("schoolClass.id = ?1 and role = ?2", id, ClassTeacherRole.ADMIN)
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .list().size();
            if (adminCount <= 1) {
                throw new BadRequestException("Der letzte verbleibende Admin kann nicht entfernt werden");
            }
        }
        boolean removingSelf = classTeacher.teacherSubject.equals(subject);
        classTeacher.delete();
        if (removingSelf) {
            String redirect = guard.hasClassAccess(schoolClass, subject) ? "/classes/" + schoolClass.id : "/classes";
            return Response.ok().header("HX-Redirect", redirect).build();
        }
        return Response.ok(classTeachersResponse(schoolClass)).build();
    }

    /** {@link Teacher} directory rows not yet a {@link ClassTeacher} on this class, for the add-teacher select. */
    private List<Teacher> availableTeachers(Long schoolClassId) {
        List<ClassTeacher> classTeachers = ClassTeacher.list("schoolClass.id", schoolClassId);
        Set<String> alreadyTeaching = new HashSet<>(classTeachers.stream().map(ct -> ct.teacherSubject).toList());
        List<Teacher> teachers = Teacher.listAll();
        return teachers.stream()
                .filter(t -> !alreadyTeaching.contains(t.subject))
                .sorted(Comparator.comparing(Teacher::displayLabel))
                .toList();
    }

    /**
     * This class's {@link ClassTeacher} rows with {@link ClassTeacher#resolvedLabel} attached
     * from the {@link Teacher} directory (batch-loaded, not one query per row) - see {@code
     * fragments/classTeachers.html}, which falls back to the raw {@code teacherSubject} when a
     * row has no matching {@link Teacher} (shouldn't normally happen, but the directory and
     * class_teacher/subject_teacher are deliberately decoupled, not FK-enforced). Also attaches
     * {@link ClassTeacher#canRemove} - false only for an {@code ADMIN}-tier row that's the last
     * one left, so the template can hide "Entfernen" without re-deriving the admin count itself.
     */
    private List<ClassTeacher> classTeachersWithResolvedLabels(Long schoolClassId) {
        List<ClassTeacher> classTeachers = ClassTeacher.list("schoolClass.id = ?1 order by id", schoolClassId);
        List<String> subjects = classTeachers.stream().map(ct -> ct.teacherSubject).toList();
        Map<String, Teacher> teachersBySubject = new HashMap<>();
        List<Teacher> knownTeachers = Teacher.list("subject in ?1", subjects);
        for (Teacher teacher : knownTeachers) {
            teachersBySubject.put(teacher.subject, teacher);
        }
        long adminCount = classTeachers.stream().filter(ct -> ct.role == ClassTeacherRole.ADMIN).count();
        for (ClassTeacher classTeacher : classTeachers) {
            Teacher teacher = teachersBySubject.get(classTeacher.teacherSubject);
            classTeacher.resolvedLabel = teacher != null ? teacher.displayLabel() : null;
            classTeacher.canRemove = !(classTeacher.role == ClassTeacherRole.ADMIN && adminCount <= 1);
        }
        return classTeachers;
    }

    private TemplateInstance classTeachersResponse(SchoolClass schoolClass) {
        return classTeachersFragment
                .data("schoolClass", schoolClass)
                .data("classTeachers", classTeachersWithResolvedLabels(schoolClass.id))
                .data("availableTeachers", availableTeachers(schoolClass.id))
                .data("currentUserSubject", currentUser.effectiveSubject());
    }

    /**
     * Sets or clears (empty submission) the class's Halbjahr cutoff date - see
     * {@link SchoolClass#halfYearCutoff}. Purely a display filter for {@link GradeGridResource};
     * changing it never touches an Assessment or Grade.
     */
    @PATCH
    @Path("/{id}/half-year-cutoff")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance updateHalfYearCutoff(@PathParam("id") Long id,
                                                  @FormParam("halfYearCutoff") LocalDate halfYearCutoff) {
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
        schoolClass.halfYearCutoff = halfYearCutoff;
        return halfYearCutoffFragment.data("schoolClass", schoolClass);
    }

    /**
     * Sets how the grade grid's H1/H2 Halbjahr columns display an average - see
     * {@link SchoolClass#halfYearGradeDisplay}/{@link SchoolClass#halfYearTendencyThreshold}
     * and {@link de.notenfuchs.service.HalfYearGradeDisplayService}. The tendency threshold is
     * valid alongside either mode - {@code HalfYearGradeDisplayService} reuses the same
     * whole-grade tendency computation in {@link HalfYearGradeDisplay#HALF}, refining it into a
     * half-grade instead of a suffix when the raw average is close enough to one.
     */
    @PATCH
    @Path("/{id}/half-year-grade-display")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance updateHalfYearGradeDisplay(@PathParam("id") Long id,
                                                         @FormParam("halfYearGradeDisplay") String halfYearGradeDisplay,
                                                         @FormParam("tendencyThreshold") BigDecimal tendencyThreshold) {
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
        HalfYearGradeDisplay mode = (halfYearGradeDisplay == null || halfYearGradeDisplay.isBlank())
                ? HalfYearGradeDisplay.WHOLE
                : HalfYearGradeDisplay.valueOf(halfYearGradeDisplay);
        schoolClass.halfYearGradeDisplay = mode;
        schoolClass.halfYearTendencyThreshold = tendencyThreshold;
        return halfYearGradeDisplayFragment.data("schoolClass", schoolClass);
    }

    @POST
    @Path("/{id}/students")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addStudent(@PathParam("id") Long id,
                                        @FormParam("name") String name,
                                        @FormParam("displayName") String displayName) {
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
        Student student = new Student();
        student.schoolClass = schoolClass;
        student.name = name;
        student.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
        student.persist();
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students).data("isClassAdmin", true);
    }

    @DELETE
    @Path("/{id}/students/{studentId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId) {
        String subject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassAdmin(id, subject);
        Student student = guard.requireRosterManageStudent(studentId, subject);
        student.delete();
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students).data("isClassAdmin", true);
    }

    @PATCH
    @Path("/{id}/students/{studentId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameStudent(@PathParam("id") Long id, @PathParam("studentId") Long studentId,
                                           @FormParam("name") String name,
                                           @FormParam("displayName") String displayName) {
        String subject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassAdmin(id, subject);
        Student student = guard.requireRosterManageStudent(studentId, subject);
        if (name != null && !name.isBlank()) {
            student.name = name;
        }
        student.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        return studentListFragment.data("schoolClass", schoolClass).data("students", students).data("isClassAdmin", true);
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
        SchoolClass schoolClass = guard.requireClassAccess(id, currentUser.effectiveSubject());
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", id);
        List<String> names = students.stream().map(s -> s.name).toList();
        byte[] csv = csvRosterService.format(names);

        String plainFilename = "klasse-" + schoolClass.name + "-schueler.csv";
        String asciiFilename = "klasse-" + DownloadFilenames.sanitize(schoolClass.name) + "-schueler.csv";
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
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
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

        return currentUser.withUser(rosterImportPreviewTemplate
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
        SchoolClass schoolClass = guard.requireClassAdmin(id, currentUser.effectiveSubject());
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
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassTeacher(id, currentSubject);
        GradeScale gradeScale = GradeScale.findById(gradeScaleId);
        if (gradeScale == null) {
            throw new NotFoundException("GradeScale " + gradeScaleId + " not found");
        }
        Subject subject = new Subject();
        subject.schoolClass = schoolClass;
        subject.name = name;
        subject.gradeScale = gradeScale;
        subject.roundingMode = (roundingMode == null || roundingMode.isBlank())
                ? RoundingMode.IN_FAVOR_OF_STUDENT
                : RoundingMode.valueOf(roundingMode);
        subject.persist();

        SubjectTeacher teacher = new SubjectTeacher();
        teacher.subject = subject;
        teacher.teacherSubject = currentSubject;
        teacher.persist();

        List<Subject> subjects = subjectsWithGradeScale(id);
        attachTeacherLabels(subjects);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects)
                .data("taughtSubjectIds", taughtSubjectIds(id, currentSubject))
                .data("isClassAdmin", guard.isAdmin(schoolClass, currentSubject))
                .data("isClassTeacher", guard.isClassTeacher(schoolClass, currentSubject));
    }

    @DELETE
    @Path("/{id}/subjects/{subjectId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteSubject(@PathParam("id") Long id, @PathParam("subjectId") Long subjectId) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireCanDeleteSubject(subjectId, currentSubject);
        if (!subject.schoolClass.id.equals(id)) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        SchoolClass schoolClass = subject.schoolClass;
        subject.delete();
        List<Subject> subjects = subjectsWithGradeScale(id);
        attachTeacherLabels(subjects);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects)
                .data("taughtSubjectIds", taughtSubjectIds(id, currentSubject))
                .data("isClassAdmin", guard.isAdmin(schoolClass, currentSubject))
                .data("isClassTeacher", guard.isClassTeacher(schoolClass, currentSubject));
    }

    @PATCH
    @Path("/{id}/subjects/{subjectId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameSubject(@PathParam("id") Long id, @PathParam("subjectId") Long subjectId,
                                           @FormParam("name") String name,
                                           @FormParam("roundingMode") String roundingMode) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(subjectId, currentSubject);
        if (!subject.schoolClass.id.equals(id)) {
            throw new NotFoundException("Subject " + subjectId + " not found");
        }
        SchoolClass schoolClass = subject.schoolClass;
        if (name != null && !name.isBlank()) {
            subject.name = name;
        }
        if (roundingMode != null && !roundingMode.isBlank()) {
            subject.roundingMode = RoundingMode.valueOf(roundingMode);
        }
        List<Subject> subjects = subjectsWithGradeScale(id);
        attachTeacherLabels(subjects);
        return subjectListFragment.data("schoolClass", schoolClass).data("subjects", subjects)
                .data("taughtSubjectIds", taughtSubjectIds(id, currentSubject))
                .data("isClassAdmin", guard.isAdmin(schoolClass, currentSubject))
                .data("isClassTeacher", guard.isClassTeacher(schoolClass, currentSubject));
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

    /**
     * Ids of the classes the given teacher is an ADMIN-tier {@link ClassTeacher} on - used by
     * {@code fragments/classList.html} to hide Ändern/Löschen for classes the viewer sees in
     * their list (via {@link OwnershipGuard#listAccessibleClasses}) but isn't an admin of, since
     * both would otherwise 404 via {@link OwnershipGuard#requireClassAdmin}.
     */
    private Set<Long> adminClassIds(String currentSubject) {
        List<ClassTeacher> myAdminRows = ClassTeacher.list(
                "teacherSubject = ?1 and role = ?2", currentSubject, ClassTeacherRole.ADMIN);
        Set<Long> ids = new HashSet<>();
        for (ClassTeacher row : myAdminRows) {
            ids.add(row.schoolClass.id);
        }
        return ids;
    }

    /**
     * Ids of this class's Subjects the given teacher actually teaches - used by
     * {@code fragments/subjectList.html} to hide Noten/Ändern/Löschen for subjects the viewer
     * has class-wide (owner or another-subject) access to but isn't a {@link SubjectTeacher} of,
     * since those would otherwise 404 via {@link OwnershipGuard#requireTeachesSubject}.
     */
    private Set<Long> taughtSubjectIds(Long schoolClassId, String currentSubject) {
        List<SubjectTeacher> myTeachingRows = SubjectTeacher.list(
                "teacherSubject = ?1 and subject.schoolClass.id = ?2", currentSubject, schoolClassId);
        Set<Long> ids = new HashSet<>();
        for (SubjectTeacher row : myTeachingRows) {
            ids.add(row.subject.id);
        }
        return ids;
    }

    /**
     * Batch-attaches {@link Subject#primaryTeacherLabel}/{@link Subject#otherTeacherLabels} from
     * the {@link Teacher} directory (one query per lookup, not one per Subject) so {@code
     * fragments/subjectList.html} can show who teaches each Subject - regardless of whether the
     * viewer teaches it themselves, since this is precisely what lets a class admin see contact
     * info per Fach and notice which teachers have class access only via teaching a Subject, not
     * a {@link ClassTeacher} row.
     */
    private void attachTeacherLabels(List<Subject> subjects) {
        if (subjects.isEmpty()) {
            return;
        }
        List<Long> subjectIds = subjects.stream().map(s -> s.id).toList();
        List<SubjectTeacher> teacherRows = SubjectTeacher.list("subject.id in ?1 order by id", subjectIds);
        List<String> teacherSubjects = teacherRows.stream().map(st -> st.teacherSubject).distinct().toList();
        Map<String, Teacher> teachersBySubject = new HashMap<>();
        List<Teacher> knownTeachers = Teacher.list("subject in ?1", teacherSubjects);
        for (Teacher teacher : knownTeachers) {
            teachersBySubject.put(teacher.subject, teacher);
        }
        Map<Long, List<String>> labelsBySubjectId = new HashMap<>();
        for (SubjectTeacher row : teacherRows) {
            Teacher teacher = teachersBySubject.get(row.teacherSubject);
            String label = teacher != null ? teacher.displayLabel() : row.teacherSubject;
            labelsBySubjectId.computeIfAbsent(row.subject.id, k -> new ArrayList<>()).add(label);
        }
        for (Subject subject : subjects) {
            List<String> labels = labelsBySubjectId.getOrDefault(subject.id, List.of());
            subject.primaryTeacherLabel = labels.isEmpty() ? null : labels.get(0);
            subject.otherTeacherLabels = labels.size() > 1 ? labels.subList(1, labels.size()) : List.of();
        }
    }

    /**
     * Teachers who can see this whole class (roster, Verhaltensnoten) purely by teaching one of
     * its Subjects (see {@link OwnershipGuard#hasClassAccess}), holding no {@link ClassTeacher}
     * row of their own here - shown read-only on the class page so an admin isn't surprised by
     * who else has that access, since {@link SubjectTeacher} additions are self-service and
     * never require the class admin's approval. Purely informational: that access is granted and
     * revoked on the Subject itself (see {@code fragments/subjectTeachers.html}), not here.
     */
    public record TransitiveTeacher(String label, String subjectNames) {
    }

    private List<TransitiveTeacher> transitiveTeachers(Long schoolClassId) {
        List<ClassTeacher> classTeachers = ClassTeacher.list("schoolClass.id", schoolClassId);
        Set<String> classTeacherSubjects = new HashSet<>(classTeachers.stream().map(ct -> ct.teacherSubject).toList());

        List<SubjectTeacher> subjectTeacherRows = SubjectTeacher.find(
                "from SubjectTeacher st join fetch st.subject where st.subject.schoolClass.id = ?1 order by st.id",
                schoolClassId).list();
        Map<String, List<String>> subjectNamesByTeacher = new HashMap<>();
        for (SubjectTeacher row : subjectTeacherRows) {
            if (classTeacherSubjects.contains(row.teacherSubject)) {
                continue;
            }
            subjectNamesByTeacher.computeIfAbsent(row.teacherSubject, k -> new ArrayList<>()).add(row.subject.name);
        }
        if (subjectNamesByTeacher.isEmpty()) {
            return List.of();
        }

        List<Teacher> knownTeachers = Teacher.list("subject in ?1", new ArrayList<>(subjectNamesByTeacher.keySet()));
        Map<String, Teacher> teachersBySubject = new HashMap<>();
        for (Teacher teacher : knownTeachers) {
            teachersBySubject.put(teacher.subject, teacher);
        }
        List<TransitiveTeacher> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : subjectNamesByTeacher.entrySet()) {
            Teacher teacher = teachersBySubject.get(entry.getKey());
            String label = teacher != null ? teacher.displayLabel() : entry.getKey();
            result.add(new TransitiveTeacher(label, String.join(", ", entry.getValue())));
        }
        result.sort(Comparator.comparing(TransitiveTeacher::label));
        return result;
    }

    /** One row of the roster import preview - see {@code ClassPage/rosterImportPreview.html}. */
    public record RosterPreviewRow(String name, boolean duplicate) {
    }

    /**
     * Buckets an already {@code schoolYear desc}-sorted class list (see
     * {@link OwnershipGuard#listAccessibleClasses}) into consecutive same-year groups, so
     * {@code fragments/classList.html} can render a year heading per group instead of one
     * long undifferentiated grid - a class a year adds up over a teaching career.
     */
    private List<YearGroup> groupByYear(List<SchoolClass> classes) {
        List<YearGroup> groups = new ArrayList<>();
        String currentYear = null;
        List<SchoolClass> bucket = null;
        for (SchoolClass schoolClass : classes) {
            if (bucket == null || !schoolClass.schoolYear.equals(currentYear)) {
                bucket = new ArrayList<>();
                groups.add(new YearGroup(schoolClass.schoolYear, bucket));
                currentYear = schoolClass.schoolYear;
            }
            bucket.add(schoolClass);
        }
        return groups;
    }

    /** One Schuljahr's worth of classes, in the class list's year-grouped display. */
    public record YearGroup(String schoolYear, List<SchoolClass> classes) {
    }
}
