package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
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
import java.util.List;

/**
 * Server-rendered HTML pages for managing a subject's grade categories and
 * assessments ("Leistungen"). The grade-entry grid itself lives in
 * {@link GradeGridResource}.
 */
@Path("/subjects")
public class SubjectUiResource {

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
        return withUser(detailTemplate
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
                                           @FormParam("factor") BigDecimal factor) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        GradeCategory category = guard.requireOwnedCategory(categoryId, currentSubject);
        Assessment assessment = new Assessment();
        assessment.category = category;
        assessment.name = name;
        assessment.date = date;
        assessment.factor = factor != null ? factor : BigDecimal.ONE;
        assessment.persist();
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

    @PATCH
    @Path("/{id}/categories/{categoryId}/assessments/{assessmentId}/rename")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance renameAssessment(@PathParam("id") Long id,
                                              @PathParam("categoryId") Long categoryId,
                                              @PathParam("assessmentId") Long assessmentId,
                                              @FormParam("name") String name,
                                              @FormParam("factor") BigDecimal factor) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);
        if (name != null && !name.isBlank()) {
            assessment.name = name;
        }
        if (factor != null) {
            assessment.factor = factor;
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
        List<CategoryView> result = new java.util.ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            result.add(new CategoryView(category.id, category.name, category.weightPercent, assessments));
        }
        return result;
    }

    /**
     * View model exposing a {@link GradeCategory} together with its {@link Assessment}s,
     * since Qute can only navigate properties/getters that actually exist on the entity.
     */
    public record CategoryView(Long id, String name, BigDecimal weightPercent, List<Assessment> assessments) {
    }

    private TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", currentUser.isAuthenticated())
                .data("currentUserDisplayName", currentUser.displayName().orElse(""));
    }
}
