package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectTeacher;
import de.notenfuchs.domain.Teacher;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.PointsConversionService;
import de.notenfuchs.service.PointsGradeBandData;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-rendered HTML pages for managing a subject's grade categories and
 * assessments ("Leistungen"), including a points-based assessment's Notenschlüssel
 * (points-threshold-to-grade bands). The grade-entry grid itself lives in
 * {@link GradeGridResource}.
 */
@Path("/subjects")
public class SubjectUiResource {

    private final PointsConversionService pointsConversionService = new PointsConversionService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    @Location("SubjectPage/detail.html")
    Template detailTemplate;

    @Inject
    @Location("SubjectPage/teachers.html")
    Template teachersTemplate;

    @Inject
    @Location("fragments/categoryList.html")
    Template categoryListFragment;

    @Inject
    @Location("fragments/subjectTeachers.html")
    Template subjectTeachersFragment;

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        CategoryListData data = categoryListData(id);
        return currentUser.withUser(detailTemplate
                .data("subject", subject)
                .data("switchSubjects", switchSubjects(subject))
                .data("categories", data.categories())
                .data("weightSum", data.weightSum())
                .data("weightSumWarning", data.weightSumWarning()));
    }

    @GET
    @Path("/{id}/teachers")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance teachersPage(@PathParam("id") Long id) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        return currentUser.withUser(teachersTemplate
                .data("subject", subject)
                .data("switchSubjects", switchSubjects(subject))
                .data("subjectTeachers", subjectTeachersWithResolvedLabels(id))
                .data("availableTeachers", availableTeachers(id))
                .data("currentUserSubject", currentUser.effectiveSubject()));
    }

    /**
     * Adds a Fachlehrer ({@link SubjectTeacher}) to the subject - self-service, gated purely by
     * {@link OwnershipGuard#requireTeachesSubject} like every other endpoint in this class, NOT
     * owner-only: any current teacher of a subject can share it with a colleague directly, no
     * class-owner approval needed (see {@code CLAUDE.md}'s Authorization section). Picks from the
     * {@link Teacher} directory the same way {@code ClassUiResource#addClassTeacher} does.
     */
    @POST
    @Path("/{id}/teachers")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance addSubjectTeacher(@PathParam("id") Long id, @FormParam("teacherSubject") String teacherSubject) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        Teacher knownTeacher = Teacher.find("subject", teacherSubject).firstResult();
        if (knownTeacher == null) {
            throw new BadRequestException("Unbekannte Lehrkraft");
        }
        if (SubjectTeacher.count("subject.id = ?1 and teacherSubject = ?2", id, teacherSubject) > 0) {
            throw new BadRequestException("Diese Lehrkraft unterrichtet dieses Fach bereits");
        }
        SubjectTeacher subjectTeacher = new SubjectTeacher();
        subjectTeacher.subject = subject;
        subjectTeacher.teacherSubject = teacherSubject;
        try {
            // Flushed immediately so a double-submitted request that raced past the count check
            // above surfaces here as the same clean 400, not an uncaught constraint-violation 500.
            subjectTeacher.persistAndFlush();
        } catch (PersistenceException e) {
            throw new BadRequestException("Diese Lehrkraft unterrichtet dieses Fach bereits");
        }
        return subjectTeachersResponse(subject);
    }

    /**
     * Removes a Fachlehrer - self-service like {@link #addSubjectTeacher}. Guarded so a subject
     * always keeps at least one {@link SubjectTeacher} row (primary defense is the template not
     * rendering "Entfernen" next to the last row; this check is defense-in-depth). Self-removal is
     * special-cased, same as {@code ClassUiResource#removeClassTeacher}: losing your one
     * {@code SubjectTeacher} row on THIS subject doesn't necessarily cost class access at all - you
     * might still own the class, or teach another subject in it. So the redirect target is computed
     * from {@link OwnershipGuard#hasClassAccess}, checked right after the delete (same transaction),
     * rather than hardcoded.
     */
    @DELETE
    @Path("/{id}/teachers/{teacherId}")
    @Transactional
    public Response removeSubjectTeacher(@PathParam("id") Long id, @PathParam("teacherId") Long teacherId) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        SubjectTeacher subjectTeacher = guard.requireTeachesSubjectTeacher(teacherId, currentSubject);
        if (!subjectTeacher.subject.id.equals(id)) {
            throw new NotFoundException("SubjectTeacher " + teacherId + " not found");
        }
        // Locks every SubjectTeacher row of this subject for the rest of the transaction, so a
        // second, concurrent removal request for the same subject blocks here until this one commits
        // or rolls back - closing the TOCTOU race a plain count-then-delete would have.
        long teacherCount = SubjectTeacher.find("subject.id", id)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list().size();
        if (teacherCount <= 1) {
            throw new BadRequestException("Die letzte verbleibende Lehrkraft kann nicht entfernt werden");
        }
        boolean removingSelf = subjectTeacher.teacherSubject.equals(currentSubject);
        SchoolClass schoolClass = subject.schoolClass;
        subjectTeacher.delete();
        if (removingSelf) {
            String redirect = guard.hasClassAccess(schoolClass, currentSubject)
                    ? "/classes/" + schoolClass.id
                    : "/classes";
            return Response.ok().header("HX-Redirect", redirect).build();
        }
        return Response.ok(subjectTeachersResponse(subject)).build();
    }

    /** {@link Teacher} directory rows not yet a {@link SubjectTeacher} on this subject, for the add-teacher select. */
    private List<Teacher> availableTeachers(Long subjectId) {
        List<SubjectTeacher> subjectTeachers = SubjectTeacher.list("subject.id", subjectId);
        Set<String> alreadyTeaching = new HashSet<>(subjectTeachers.stream().map(st -> st.teacherSubject).toList());
        List<Teacher> teachers = Teacher.listAll();
        return teachers.stream()
                .filter(t -> !alreadyTeaching.contains(t.subject))
                .sorted(Comparator.comparing(Teacher::displayLabel))
                .toList();
    }

    /**
     * This subject's {@link SubjectTeacher} rows with {@link SubjectTeacher#resolvedLabel}
     * attached from the {@link Teacher} directory (batch-loaded, not one query per row).
     */
    private List<SubjectTeacher> subjectTeachersWithResolvedLabels(Long subjectId) {
        List<SubjectTeacher> subjectTeachers = SubjectTeacher.list("subject.id = ?1 order by id", subjectId);
        List<String> subjects = subjectTeachers.stream().map(st -> st.teacherSubject).toList();
        Map<String, Teacher> teachersBySubject = new HashMap<>();
        List<Teacher> knownTeachers = Teacher.list("subject in ?1", subjects);
        for (Teacher teacher : knownTeachers) {
            teachersBySubject.put(teacher.subject, teacher);
        }
        for (SubjectTeacher subjectTeacher : subjectTeachers) {
            Teacher teacher = teachersBySubject.get(subjectTeacher.teacherSubject);
            subjectTeacher.resolvedLabel = teacher != null ? teacher.displayLabel() : null;
        }
        return subjectTeachers;
    }

    private TemplateInstance subjectTeachersResponse(Subject subject) {
        return subjectTeachersFragment
                .data("subject", subject)
                .data("subjectTeachers", subjectTeachersWithResolvedLabels(subject.id))
                .data("availableTeachers", availableTeachers(subject.id))
                .data("currentUserSubject", currentUser.effectiveSubject());
    }

    @POST
    @Path("/{id}/categories")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addCategory(@PathParam("id") Long id,
                                         @FormParam("name") String name,
                                         @FormParam("weightPercent") BigDecimal weightPercent) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        GradeCategory category = new GradeCategory();
        category.subject = subject;
        category.name = name;
        category.weightPercent = weightPercent;
        category.persist();
        return categoryFragment(subject);
    }

    @DELETE
    @Path("/{id}/categories/{categoryId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteCategory(@PathParam("id") Long id, @PathParam("categoryId") Long categoryId) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        GradeCategory category = guard.requireTeachesCategory(categoryId, currentSubject);
        category.delete();
        return categoryFragment(subject);
    }

    @PATCH
    @Path("/{id}/categories/{categoryId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameCategory(@PathParam("id") Long id, @PathParam("categoryId") Long categoryId,
                                            @FormParam("name") String name,
                                            @FormParam("weightPercent") BigDecimal weightPercent) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        GradeCategory category = guard.requireTeachesCategory(categoryId, currentSubject);
        if (name != null && !name.isBlank()) {
            category.name = name;
        }
        if (weightPercent != null) {
            category.weightPercent = weightPercent;
        }
        return categoryFragment(subject);
    }

    /** Shared with {@code GradeGridResource}: the Fach header's switcher lists the viewer's Fächer of the same year. */
    List<Subject> switchSubjects(Subject subject) {
        return guard.listTaughtSubjectsInYear(currentUser.effectiveSubject(), subject.schoolClass.schoolYear);
    }

    /** Shared with the grade table ({@code GradeGridResource}) so both create a Leistung the same way. */
    Assessment createAssessment(GradeCategory category, GradeScale scale, String name, LocalDate date,
                                BigDecimal factor, String pointsBasedRaw, String roundingModeRaw) {
        boolean pointsBased = isChecked(pointsBasedRaw);
        Assessment assessment = new Assessment();
        assessment.category = category;
        assessment.name = name;
        assessment.date = date;
        assessment.factor = factor != null ? factor : BigDecimal.ONE;
        assessment.pointsBased = pointsBased;
        assessment.roundingMode = parseRoundingMode(roundingModeRaw);
        assessment.persist();
        if (pointsBased) {
            seedDefaultBands(assessment, scale);
        }
        return assessment;
    }

    /**
     * Shared with the grade table's Leistung dialog. Flipping {@code pointsBased} clears the grades
     * (a grade in one mode means nothing in the other) and a switch to points seeds default bands.
     */
    void updateAssessment(Assessment assessment, GradeScale scale, String name, BigDecimal factor,
                          String pointsBasedRaw, String roundingModeRaw, LocalDate date) {
        if (name != null && !name.isBlank()) {
            assessment.name = name;
        }
        if (factor != null) {
            assessment.factor = factor;
        }
        boolean pointsBased = isChecked(pointsBasedRaw);
        if (pointsBased != assessment.pointsBased) {
            // A grade entered in one mode means nothing in the other; an absence still happened.
            Grade.delete("assessment.id = ?1 and absence is null", assessment.id);
            Grade.update("value = null, points = null where assessment.id = ?1", assessment.id);
        }
        assessment.pointsBased = pointsBased;
        assessment.roundingMode = parseRoundingMode(roundingModeRaw);
        if (pointsBased && PointsGradeBand.count("assessment.id", assessment.id) == 0) {
            seedDefaultBands(assessment, scale);
        }
        assessment.date = date;
    }

    /** A checkbox form field arrives as "true"/"on" when checked, or absent (null) when not. */
    private static boolean isChecked(String rawValue) {
        return "true".equals(rawValue) || "on".equals(rawValue);
    }

    /** A blank/absent rounding-mode select falls back to the same default as a fresh Assessment. */
    private static RoundingMode parseRoundingMode(String rawValue) {
        return rawValue == null || rawValue.isBlank() ? RoundingMode.IN_FAVOR_OF_STUDENT : RoundingMode.valueOf(rawValue);
    }

    /** Seeds a fresh points-based Assessment with {@link PointsConversionService#defaultBands}. */
    private void seedDefaultBands(Assessment assessment, GradeScale scale) {
        for (PointsGradeBandData data : pointsConversionService.defaultBands(scale)) {
            PointsGradeBand band = new PointsGradeBand();
            band.assessment = assessment;
            band.points = data.points();
            band.gradeValue = data.gradeValue();
            band.persist();
        }
    }

    private TemplateInstance categoryFragment(Subject subject) {
        CategoryListData data = categoryListData(subject.id);
        return categoryListFragment
                .data("subject", subject)
                .data("categories", data.categories())
                .data("weightSum", data.weightSum())
                .data("weightSumWarning", data.weightSumWarning());
    }

    /**
     * Categories, their weight sum and whether that sum deviates from 100% -
     * shared by the full-page render and the htmx fragment so both stay in
     * sync (previously computed separately, and the full-page render forgot
     * weightSum/weightSumWarning entirely).
     */
    private CategoryListData categoryListData(Long subjectId) {
        List<CategoryView> categories = categoryViews(subjectId);
        BigDecimal weightSum = categories.stream()
                .map(CategoryView::weightPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean weightSumWarning = !categories.isEmpty() && weightSum.compareTo(new BigDecimal("100")) != 0;
        return new CategoryListData(categories, weightSum, weightSumWarning);
    }

    private record CategoryListData(List<CategoryView> categories, BigDecimal weightSum, boolean weightSumWarning) {
    }

    /**
     * {@link GradeCategory} has no back-reference to its {@link Assessment}s (Assessment
     * only points at its category), so this view model loads them explicitly for
     * rendering the category+assessment list in the Qute template.
     */
    private List<CategoryView> categoryViews(Long subjectId) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subjectId);
        Map<Long, String> bandSpecs = GradeGridResource.bandSpecs(subjectId);
        List<CategoryView> result = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            List<AssessmentView> assessmentViews = new ArrayList<>();
            for (Assessment assessment : assessments) {
                assessmentViews.add(new AssessmentView(assessment.id, assessment.name, assessment.date,
                        assessment.factor, assessment.pointsBased, bandSpecs.getOrDefault(assessment.id, ""),
                        assessment.roundingMode));
            }
            result.add(new CategoryView(category.id, category.name, category.weightPercent, assessmentViews));
        }
        return result;
    }

    /**
     * View model exposing a {@link GradeCategory} together with its {@link Assessment}s,
     * since Qute can only navigate properties/getters that actually exist on the entity.
     */
    public record CategoryView(Long id, String name, BigDecimal weightPercent, List<AssessmentView> assessments) {
    }

    /** {@code bandSpec} is the Notenschlüssel in the form the Leistung dialog reads ("60:1;20:6"). */
    public record AssessmentView(Long id, String name, LocalDate date, BigDecimal factor,
                                  boolean pointsBased, String bandSpec, RoundingMode roundingMode) {
    }

}
