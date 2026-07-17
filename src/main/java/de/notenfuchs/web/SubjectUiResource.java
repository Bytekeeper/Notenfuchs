package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.RoundingMode;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.PointsConversionService;
import de.notenfuchs.service.PointsGradeBandData;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
    @Location("fragments/categoryList.html")
    Template categoryListFragment;

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
        CategoryListData data = categoryListData(id);
        return currentUser.withUser(detailTemplate
                .data("subject", subject)
                .data("categories", data.categories())
                .data("weightSum", data.weightSum())
                .data("weightSumWarning", data.weightSumWarning()));
    }

    @POST
    @Path("/{id}/categories")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addCategory(@PathParam("id") Long id,
                                         @FormParam("name") String name,
                                         @FormParam("weightPercent") BigDecimal weightPercent) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
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
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        GradeCategory category = guard.requireOwnedCategory(categoryId, currentSubject);
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
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        GradeCategory category = guard.requireOwnedCategory(categoryId, currentSubject);
        if (name != null && !name.isBlank()) {
            category.name = name;
        }
        if (weightPercent != null) {
            category.weightPercent = weightPercent;
        }
        return categoryFragment(subject);
    }

    @POST
    @Path("/{id}/categories/{categoryId}/assessments")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addAssessment(@PathParam("id") Long id,
                                           @PathParam("categoryId") Long categoryId,
                                           @FormParam("name") String name,
                                           @FormParam("date") LocalDate date,
                                           @FormParam("factor") BigDecimal factor,
                                           @FormParam("pointsBased") String pointsBasedRaw,
                                           @FormParam("roundingMode") String roundingModeRaw) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        GradeCategory category = guard.requireOwnedCategory(categoryId, currentSubject);
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
            seedDefaultBands(assessment, subject.gradeScale);
        }
        return categoryFragment(subject);
    }

    @DELETE
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteAssessment(@PathParam("id") Long id,
                                              @PathParam("categoryId") Long categoryId,
                                              @PathParam("assessmentId") Long assessmentId) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);
        assessment.delete();
        return categoryFragment(subject);
    }

    /**
     * Renames an assessment and/or updates its factor, points-based configuration and (for a
     * points-based assessment) rounding mode in one form (the rename-wrap's edit form,
     * extended with a "Punktebasiert" checkbox and a rounding-mode select). Flipping
     * {@code pointsBased} either direction wipes this assessment's existing
     * {@link Grade}s - a grade entered in one mode (direct value vs. raw points) has no
     * meaning in the other - and switching TO points-based seeds a starting Notenschlüssel if
     * none exists yet (see {@link #seedDefaultBands}).
     */
    @PATCH
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameAssessment(@PathParam("id") Long id,
                                              @PathParam("categoryId") Long categoryId,
                                              @PathParam("assessmentId") Long assessmentId,
                                              @FormParam("name") String name,
                                              @FormParam("factor") BigDecimal factor,
                                              @FormParam("pointsBased") String pointsBasedRaw,
                                              @FormParam("roundingMode") String roundingModeRaw) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);
        if (name != null && !name.isBlank()) {
            assessment.name = name;
        }
        if (factor != null) {
            assessment.factor = factor;
        }
        boolean pointsBased = isChecked(pointsBasedRaw);
        if (pointsBased != assessment.pointsBased) {
            Grade.delete("assessment.id", assessment.id);
        }
        assessment.pointsBased = pointsBased;
        assessment.roundingMode = parseRoundingMode(roundingModeRaw);
        if (pointsBased && PointsGradeBand.count("assessment.id", assessment.id) == 0) {
            seedDefaultBands(assessment, subject.gradeScale);
        }
        return categoryFragment(subject);
    }

    /**
     * Separate from {@link #renameAssessment}: date is optional (nullable in the DB), so an
     * absent form field there can't be told apart from "clear it" - a dedicated endpoint for
     * a dedicated date-only form sidesteps that ambiguity, since here an empty submission
     * unambiguously means "clear the date".
     */
    @PATCH
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/date")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance changeAssessmentDate(@PathParam("id") Long id,
                                                  @PathParam("categoryId") Long categoryId,
                                                  @PathParam("assessmentId") Long assessmentId,
                                                  @FormParam("date") LocalDate date) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);
        assessment.date = date;
        return categoryFragment(subject);
    }

    @POST
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/bands")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance addBand(@PathParam("id") Long id,
                                     @PathParam("categoryId") Long categoryId,
                                     @PathParam("assessmentId") Long assessmentId,
                                     @FormParam("minPoints") BigDecimal minPoints,
                                     @FormParam("gradeValue") BigDecimal gradeValue) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);
        PointsGradeBand band = new PointsGradeBand();
        band.assessment = assessment;
        band.minPoints = minPoints;
        band.gradeValue = gradeValue;
        band.persist();
        return categoryFragment(subject);
    }

    @PATCH
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/bands/{bandId}")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance updateBand(@PathParam("id") Long id,
                                        @PathParam("categoryId") Long categoryId,
                                        @PathParam("assessmentId") Long assessmentId,
                                        @PathParam("bandId") Long bandId,
                                        @FormParam("minPoints") BigDecimal minPoints,
                                        @FormParam("gradeValue") BigDecimal gradeValue) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        PointsGradeBand band = guard.requireOwnedPointsGradeBand(bandId, currentSubject);
        if (minPoints != null) {
            band.minPoints = minPoints;
        }
        if (gradeValue != null) {
            band.gradeValue = gradeValue;
        }
        return categoryFragment(subject);
    }

    @DELETE
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/bands/{bandId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance deleteBand(@PathParam("id") Long id,
                                        @PathParam("categoryId") Long categoryId,
                                        @PathParam("assessmentId") Long assessmentId,
                                        @PathParam("bandId") Long bandId) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        PointsGradeBand band = guard.requireOwnedPointsGradeBand(bandId, currentSubject);
        band.delete();
        return categoryFragment(subject);
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
            band.minPoints = data.minPoints();
            band.gradeValue = data.gradeValue();
            band.persist();
        }
    }

    private TemplateInstance categoryFragment(Subject subject) {
        // The band editor's min/max attributes read subject.gradeScale (a LAZY association).
        // These callers are all @Transactional endpoints, and Qute renders the returned
        // TemplateInstance after the method returns - once the transaction (and its Hibernate
        // session) has already closed - so the proxy must be force-initialized here first.
        org.hibernate.Hibernate.initialize(subject.gradeScale);
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
        List<CategoryView> result = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            List<AssessmentView> assessmentViews = new ArrayList<>();
            for (Assessment assessment : assessments) {
                List<PointsGradeBand> bands = assessment.pointsBased
                        ? PointsGradeBand.list("assessment.id = ?1 order by minPoints desc", assessment.id)
                        : List.of();
                assessmentViews.add(new AssessmentView(assessment.id, assessment.name, assessment.date,
                        assessment.factor, assessment.pointsBased, bands, assessment.roundingMode));
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

    /**
     * View model exposing an {@link Assessment} together with its {@link PointsGradeBand}s
     * (only loaded/populated when {@code pointsBased}), since {@code Assessment} has no
     * back-reference to its bands. {@code roundingMode} only matters while {@code pointsBased}
     * is true.
     */
    public record AssessmentView(Long id, String name, LocalDate date, BigDecimal factor,
                                  boolean pointsBased, List<PointsGradeBand> bands, RoundingMode roundingMode) {
    }

}
