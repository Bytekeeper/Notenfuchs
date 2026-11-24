package de.notenfuchs.web;

import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.domain.SubjectPointsKey;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.PercentStepData;
import de.notenfuchs.service.PointsConversionService;
import de.notenfuchs.service.PointsGradeBandData;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A Fach's Notenschlüssel in percent, on "Leistungen & Gewichtung". A new points Leistung turns it
 * into rows for its maximum points; Leistungen keep their own rows afterwards.
 */
@Path("/subjects/{id}/points-key")
public class SubjectPointsKeyResource {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final PointsConversionService pointsConversionService = new PointsConversionService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    @Location("fragments/pointsKey.html")
    Template pointsKeyFragment;

    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance save(@PathParam("id") Long id,
                                 @FormParam("grade") List<String> grades,
                                 @FormParam("percent") List<BigDecimal> percents,
                                 @FormParam("sliding") String slidingRaw) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        GradeScale scale = subject.gradeScale;
        if (grades == null || percents == null || grades.isEmpty() || grades.size() != percents.size()) {
            throw new BadRequestException("Der Notenschlüssel braucht mindestens eine vollständige Zeile.");
        }
        List<PointsGradeBandData> rows = new ArrayList<>();
        for (int i = 0; i < grades.size(); i++) {
            BigDecimal percent = percents.get(i);
            if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
                throw new BadRequestException("Prozentwerte liegen zwischen 0 und 100.");
            }
            rows.add(new PointsGradeBandData(percent, GradeGridResource.parseKeyGrade(grades.get(i), scale)));
        }
        if (!pointsConversionService.isOrdered(rows, scale)) {
            throw new BadRequestException("Eine bessere Note braucht einen höheren Prozentwert als eine schlechtere.");
        }
        SubjectPointsKey.delete("subject.id", id);
        for (PointsGradeBandData row : rows) {
            SubjectPointsKey saved = new SubjectPointsKey();
            saved.subject = subject;
            saved.gradeValue = row.gradeValue();
            saved.minPercent = row.points();
            saved.persist();
        }
        subject.pointsKeySliding = "true".equals(slidingRaw) || "on".equals(slidingRaw);
        return withKey(pointsKeyFragment.data("subject", subject), subject).data("saved", true);
    }

    /** What {@code fragments/pointsKey.html} and the Leistung dialog read; the pages that include them call this too. */
    static TemplateInstance withKey(TemplateInstance template, Subject subject) {
        GradeScale scale = subject.gradeScale;
        List<KeyRow> rows = keyOf(subject).stream()
                .map(row -> new KeyRow(subject.formatGrade(row.gradeValue()), row.minPercent()))
                .toList();
        return template
                .data("keyRows", rows)
                .data("keySliding", subject.pointsKeySliding)
                .data("worstGrade", subject.formatGrade(scale.lowerIsBetter ? scale.max : scale.min))
                .data("keySpec", spec(subject, rows));
    }

    public record KeyRow(String grade, BigDecimal percent) {
    }

    /** The Fach's saved rows, best grade first, or the computed default. */
    static List<PercentStepData> keyOf(Subject subject) {
        List<SubjectPointsKey> saved = SubjectPointsKey.list("subject.id = ?1 order by minPercent desc", subject.id);
        if (saved.isEmpty()) {
            return pointsConversionService.defaultKey(subject.gradeScale);
        }
        return saved.stream().map(row -> new PercentStepData(row.gradeValue, row.minPercent)).toList();
    }

    /** The key as the Leistung dialog reads it: "1:87.5;2-:75" (grade as shown, percent with a dot). */
    private static String spec(Subject subject, List<KeyRow> rows) {
        StringBuilder spec = new StringBuilder();
        for (KeyRow row : rows) {
            if (!spec.isEmpty()) {
                spec.append(';');
            }
            spec.append(row.grade()).append(':').append(GermanNumbers.plain(row.percent()));
        }
        return spec.toString();
    }
}
