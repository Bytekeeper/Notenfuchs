package de.notenfuchs.web;

import de.notenfuchs.domain.Absence;
import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
import de.notenfuchs.domain.ReportGrade;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.CategoryData;
import de.notenfuchs.service.GradeData;
import de.notenfuchs.service.GradeService;
import de.notenfuchs.service.HalfYearAssessmentPartitioner;
import de.notenfuchs.service.HalfYearGradeDisplayService;
import de.notenfuchs.service.PointsConversionService;
import de.notenfuchs.service.PointsGradeBandData;
import de.notenfuchs.service.SubjectAverageResult;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.notenfuchs.web.GermanNumbers.display;

/**
 * The headline feature: an Excel-like grade-entry grid for a subject (students as rows,
 * assessments as columns, grouped by category), with per-cell autosave and a live
 * per-student average column driven by {@link GradeService}.
 *
 * <p>The page itself ({@link #grid(Long)}) is a normal full HTML render. Cell edits are
 * persisted via {@link #saveCell} - a small JSON endpoint called from
 * {@code static/js/notenfuchs.js} (plain fetch, not an HTMX swap, since we need
 * fine-grained keyboard-driven navigation that HTMX's declarative triggers don't fit well).
 *
 * <p>A points-based {@link Assessment}'s column accepts raw points instead of a direct grade
 * value; the grade shown/used everywhere (cell indicator, row/column averages, .xlsx export)
 * is derived live via {@link PointsConversionService} from the stored {@link Grade#points} -
 * never frozen - so editing either the points or the assessment's Notenschlüssel bands
 * recomputes the grade automatically (see ROADMAP.md's anti-freeze design principle).
 *
 * <p>If the class has a {@code halfYearCutoff} set (see {@link de.notenfuchs.domain.SchoolClass}),
 * the grid renders a second way: Assessments are split via {@link HalfYearAssessmentPartitioner}
 * into "Ohne Datum" (undated - only shown if any exist), "1. Halbjahr" and "2. Halbjahr" column
 * blocks, each with its own subject-average column computed by calling {@link GradeService}
 * exactly as usual but with only that half's assessments - plus a final "Jahr" average column
 * over everything, identical to today's single-average behavior. This is purely a display/query
 * filter (see ROADMAP.md's "Halbjahr as a display filter only"): no new grade entities, nothing
 * frozen, {@link GradeService} itself stays completely unaware of Halbjahr. When the cutoff is
 * null, {@link #grid} and {@link #exportXlsx} render exactly as before via {@link #loadGridData}/
 * {@link #buildWorkbook} - unchanged code paths, for backward compatibility.
 */
@Path("/subjects/{id}/grid")
public class GradeGridResource {

    private final GradeService gradeService = new GradeService();
    private final PointsConversionService pointsConversionService = new PointsConversionService();
    private final HalfYearAssessmentPartitioner halfYearPartitioner = new HalfYearAssessmentPartitioner();
    private final HalfYearGradeDisplayService halfYearGradeDisplayService = new HalfYearGradeDisplayService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    SubjectUiResource subjectUi;

    @Inject
    @Location("GridPage/grid.html")
    Template gridTemplate;

    @Inject
    @Location("GridPage/gridHalfYear.html")
    Template gridHalfYearTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grid(@PathParam("id") Long id) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        // Lets the empty state offer "+ Leistung" per category before any column exists.
        List<GradeCategory> subjectCategories = GradeCategory.list("subject.id = ?1 order by id", id);
        Map<Long, String> bandSpecs = bandSpecs(id);

        if (cutoff == null) {
            GridData data = loadGridData(subject);
            return currentUser.withUser(gridTemplate
                    .data("subject", subject)
                    .data("subjectCategories", subjectCategories)
                    .data("switchSubjects", subjectUi.switchSubjects(subject))
                    .data("bandSpecs", bandSpecs)
                    .data("categories", data.categoryColumns())
                    .data("students", data.students())
                    .data("rows", data.rows())
                    .data("leftRows", data.leftRows())
                    .data("categoryFooters", data.categoryFooters())
                    .data("maxCol", data.maxCol())
                    .data("maxRow", data.maxRow())
                    .data("gridEmpty", data.gridEmpty()));
        }

        HalfYearGridData data = loadHalfYearGridData(subject, cutoff);
        return currentUser.withUser(gridHalfYearTemplate
                .data("subject", subject)
                .data("subjectCategories", subjectCategories)
                .data("switchSubjects", subjectUi.switchSubjects(subject))
                .data("bandSpecs", bandSpecs)
                .data("isClassAdmin", guard.isAdmin(subject.schoolClass, currentUser.effectiveSubject()))
                .data("cutoff", cutoff)
                // A new Leistung from a region's "+" should land in that region: today if it falls in it,
                // otherwise the nearest day that does.
                .data("h1DefaultDate", LocalDate.now().isAfter(cutoff) ? cutoff : LocalDate.now())
                .data("h2DefaultDate", LocalDate.now().isAfter(cutoff) ? LocalDate.now() : cutoff.plusDays(1))
                .data("undatedColumns", data.undatedColumns())
                .data("undatedTotalCols", data.undatedTotalCols())
                .data("h1Columns", data.h1Columns())
                .data("h1HeaderColspan", data.h1HeaderColspan())
                .data("h2Columns", data.h2Columns())
                .data("h2HeaderColspan", data.h2HeaderColspan())
                .data("students", data.students())
                .data("rows", data.rows())
                .data("leftRows", data.leftRows())
                .data("undatedFooters", data.undatedFooters())
                .data("h1Footers", data.h1Footers())
                .data("h2Footers", data.h2Footers())
                .data("maxCol", data.maxCol())
                .data("maxRow", data.maxRow())
                .data("gridEmpty", data.gridEmpty()));
    }

    /**
     * Adds a Leistung from the grade table. A plain form POST answered with a redirect (not htmx):
     * the new column changes the whole table layout, so re-rendering the page is simpler than
     * patching it. {@code focus} tells the page which column to put the cursor in. The same dialog
     * on "Leistungen & Gewichtung" sends {@code returnTo=setup} to come back there instead.
     */
    @POST
    @Path("/assessments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response addAssessment(@PathParam("id") Long id,
                                  @FormParam("categoryId") Long categoryId,
                                  @FormParam("name") String name,
                                  @FormParam("date") LocalDate date,
                                  @FormParam("factor") BigDecimal factor,
                                  @FormParam("pointsBased") String pointsBasedRaw,
                                  @FormParam("roundingMode") String roundingModeRaw,
                                  @FormParam("returnTo") String returnTo) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        GradeCategory category = guard.requireTeachesCategory(categoryId, currentSubject);
        if (!category.subject.id.equals(id)) {
            throw new NotFoundException("GradeCategory " + categoryId + " not found");
        }
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Name fehlt");
        }
        Assessment assessment = subjectUi.createAssessment(category, subject.gradeScale, name.strip(), date, factor,
                pointsBasedRaw, roundingModeRaw);
        return backFromDialog(id, assessment.id, returnTo);
    }

    @POST
    @Path("/assessments/{assessmentId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateAssessment(@PathParam("id") Long id,
                                     @PathParam("assessmentId") Long assessmentId,
                                     @FormParam("name") String name,
                                     @FormParam("date") LocalDate date,
                                     @FormParam("factor") BigDecimal factor,
                                     @FormParam("pointsBased") String pointsBasedRaw,
                                     @FormParam("roundingMode") String roundingModeRaw,
                                     @FormParam("bandPoints") List<BigDecimal> bandPoints,
                                     @FormParam("bandGrades") List<BigDecimal> bandGrades,
                                     @FormParam("returnTo") String returnTo) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        Assessment assessment = requireAssessmentOfSubject(assessmentId, id);
        boolean wasPointsBased = assessment.pointsBased;
        // Checked before anything changes, so a bad Notenschlüssel can't leave half an edit behind.
        List<PointsGradeBandData> bands = bandPoints == null || bandPoints.isEmpty() ? null
                : parseBands(bandPoints, bandGrades, subject.gradeScale);
        subjectUi.updateAssessment(assessment, subject.gradeScale, name, factor, pointsBasedRaw, roundingModeRaw, date);
        // The dialog only shows the band editor for a Leistung that was already points-based; a
        // fresh switch to points gets the default bands from updateAssessment instead.
        if (bands != null && wasPointsBased && assessment.pointsBased) {
            PointsGradeBand.delete("assessment.id", assessment.id);
            for (PointsGradeBandData data : bands) {
                PointsGradeBand band = new PointsGradeBand();
                band.assessment = assessment;
                band.points = data.points();
                band.gradeValue = data.gradeValue();
                band.persist();
            }
        }
        return backFromDialog(id, assessment.id, returnTo);
    }

    private static Response backFromDialog(Long subjectId, Long assessmentId, String returnTo) {
        return "setup".equals(returnTo)
                ? Redirects.seeOther("/subjects/" + subjectId).build()
                : Redirects.seeOther("/subjects/" + subjectId + "/grid?focus=" + assessmentId).build();
    }

    /**
     * Notenspiegel for a Notenschlüssel the teacher is still editing: how many students would get
     * each whole grade, and the average, using the same conversion and rounding as the grid.
     * Nothing is saved.
     */
    @POST
    @Path("/assessments/{assessmentId}/band-preview")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public BandPreview bandPreview(@PathParam("id") Long id,
                                   @PathParam("assessmentId") Long assessmentId,
                                   @FormParam("bandPoints") List<BigDecimal> bandPoints,
                                   @FormParam("bandGrades") List<BigDecimal> bandGrades) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        Assessment assessment = requireAssessmentOfSubject(assessmentId, id);
        List<PointsGradeBandData> bands = parseBands(bandPoints, bandGrades, subject.gradeScale);
        GradeScale scale = subject.gradeScale;
        Map<Integer, Integer> countByGrade = new HashMap<>();
        List<BigDecimal> derived = new ArrayList<>();
        for (Grade grade : Grade.<Grade>list(
                "assessment.id = ?1 and points is not null and student.leftOn is null", assessment.id)) {
            BigDecimal value = pointsConversionService.convert(grade.points, bands, scale, assessment.roundingMode);
            derived.add(value);
            Integer whole = gradeService.calculateAssessmentAverage(List.of(value), scale, subject.roundingMode).finalGrade();
            countByGrade.merge(whole, 1, Integer::sum);
        }
        List<BandPreview.GradeCount> distribution = new ArrayList<>();
        for (int g : scale.wholeGradesBestFirst()) {
            distribution.add(new BandPreview.GradeCount(g, countByGrade.getOrDefault(g, 0)));
        }
        BigDecimal average = gradeService.calculateAssessmentAverage(derived, scale, subject.roundingMode).rawAverage();
        return new BandPreview(distribution, average != null ? display(average) : null);
    }

    public record BandPreview(List<GradeCount> distribution, String average) {
        public record GradeCount(int grade, int count) {
        }
    }

    private static List<PointsGradeBandData> parseBands(List<BigDecimal> points, List<BigDecimal> grades, GradeScale scale) {
        if (points == null || grades == null || points.isEmpty() || points.size() != grades.size()) {
            throw new BadRequestException("Der Notenschlüssel braucht mindestens eine vollständige Stufe");
        }
        List<PointsGradeBandData> bands = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            BigDecimal p = points.get(i);
            BigDecimal g = grades.get(i);
            if (p == null || g == null || p.signum() < 0 || g.compareTo(scale.min) < 0 || g.compareTo(scale.max) > 0) {
                throw new BadRequestException("Ungültige Stufe im Notenschlüssel");
            }
            bands.add(new PointsGradeBandData(p, g));
        }
        return bands;
    }

    @POST
    @Path("/assessments/{assessmentId}/delete")
    @Transactional
    public Response deleteAssessment(@PathParam("id") Long id, @PathParam("assessmentId") Long assessmentId,
                                     @FormParam("returnTo") String returnTo) {
        guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        requireAssessmentOfSubject(assessmentId, id).delete();
        return Redirects.seeOther("setup".equals(returnTo) ? "/subjects/" + id : "/subjects/" + id + "/grid").build();
    }

    /**
     * One-click starter for a Fach without categories: Schriftlich 50 % / Mündlich 50 %, the most
     * common split. Does nothing if categories already exist, so a double submit can't duplicate them.
     */
    @POST
    @Path("/starter-categories")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response addStarterCategories(@PathParam("id") Long id, @FormParam("returnTo") String returnTo) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        if (GradeCategory.count("subject.id", id) == 0) {
            for (String name : List.of("Schriftlich", "Mündlich")) {
                GradeCategory category = new GradeCategory();
                category.subject = subject;
                category.name = name;
                category.weightPercent = new BigDecimal("50");
                category.persist();
            }
        }
        String target = "setup".equals(returnTo) ? "/subjects/" + id : "/subjects/" + id + "/grid";
        return Redirects.seeOther(target).build();
    }

    /** Each points-based Leistung's Notenschlüssel as "60:1;20:6" (best first), read by the Leistung dialog. */
    static Map<Long, String> bandSpecs(Long subjectId) {
        Map<Long, StringBuilder> specs = new HashMap<>();
        for (PointsGradeBand band : PointsGradeBand.<PointsGradeBand>list(
                "assessment.category.subject.id = ?1 order by points desc", subjectId)) {
            StringBuilder spec = specs.computeIfAbsent(band.assessment.id, k -> new StringBuilder());
            if (!spec.isEmpty()) {
                spec.append(';');
            }
            // Read by the dialog's number inputs, which need the dot.
            spec.append(GermanNumbers.plain(band.points)).append(':').append(GermanNumbers.plain(band.gradeValue));
        }
        Map<Long, String> result = new HashMap<>();
        specs.forEach((assessmentId, spec) -> result.put(assessmentId, spec.toString()));
        return result;
    }

    private Assessment requireAssessmentOfSubject(Long assessmentId, Long subjectId) {
        Assessment assessment = guard.requireTeachesAssessment(assessmentId, currentUser.effectiveSubject());
        if (!assessment.category.subject.id.equals(subjectId)) {
            throw new NotFoundException("Assessment " + assessmentId + " not found");
        }
        return assessment;
    }

    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportXlsx(@PathParam("id") Long id) {
        Subject subject = guard.requireTeachesSubject(id, currentUser.effectiveSubject());
        LocalDate cutoff = subject.schoolClass.halfYearCutoff;

        byte[] xlsx = cutoff == null
                ? buildWorkbook(subject, loadGridData(subject))
                : buildHalfYearWorkbook(subject, loadHalfYearGridData(subject, cutoff));

        String asciiFilename = DownloadFilenames.sanitize(subject.name) + "-Noten.xlsx";
        String utf8Filename = URLEncoder.encode(subject.name + "-Noten.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"" + asciiFilename
                        + "\"; filename*=UTF-8''" + utf8Filename)
                .build();
    }

    private GridData loadGridData(Subject subject) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<Student> allStudents = Student.list("schoolClass.id = ?1 order by name", subject.schoolClass.id);
        List<Student> students = allStudents.stream().filter(s -> !s.hasLeft()).toList();
        List<Student> leftStudents = allStudents.stream().filter(Student::hasLeft).toList();

        List<CategoryColumns> categoryColumns = new ArrayList<>();
        List<Assessment> allAssessments = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            categoryColumns.add(new CategoryColumns(category, assessments));
            allAssessments.addAll(assessments);
        }

        Map<Long, List<PointsGradeBandData>> bandsByAssessment = bandsByAssessment(allAssessments);
        Map<GradeKey, Grade> grades = gradesByKey(Grade.list("assessment.category.subject.id", subject.id));

        // Footers come from the active students only; students who left are shown, not counted.
        BlockBuild build = buildBlockCells(categoryColumns, students, subject, bandsByAssessment, grades, 0);
        List<RowView> rows = rowViews(categoryColumns, students, subject, grades, build);
        List<RowView> leftRows = rowViews(categoryColumns, leftStudents, subject, grades,
                buildBlockCells(categoryColumns, leftStudents, subject, bandsByAssessment, grades, 0));

        int maxCol = Math.max(0, totalColumns(categoryColumns) - 1);
        int maxRow = Math.max(0, rows.size() - 1);
        boolean gridEmpty = categoryColumns.isEmpty() || allAssessments.isEmpty();

        return new GridData(categoryColumns, students, rows, leftRows, build.footers(), maxCol, maxRow, gridEmpty);
    }

    private List<RowView> rowViews(List<CategoryColumns> columns, List<Student> students, Subject subject,
                                   Map<GradeKey, Grade> grades, BlockBuild build) {
        List<SubjectAverageResult> averages = subjectAveragesForBlock(columns, students, subject, grades);
        Map<Long, BigDecimal> reportGrades = ReportGrade.bySubject(subject.id, ReportGrade.Period.YEAR);
        List<RowView> rows = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            Student student = students.get(i);
            SubjectAverageResult average = averages.get(i);
            rows.add(new RowView(i, new StudentView(student.id, student.effectiveName()),
                    build.cellsByStudent().get(i), average.rawAverage(), average.finalGrade(),
                    formatOrNull(subject.gradeScale, reportGrades.get(student.id))));
        }
        return rows;
    }

    /**
     * Same idea as {@link #loadGridData}, but with each category's Assessments split into three
     * date-based buckets (see {@link HalfYearAssessmentPartitioner}) and rendered as three
     * column blocks - "Ohne Datum" (only if non-empty), "1. Halbjahr", "2. Halbjahr" - each with
     * its own subject-average column, plus a final "Jahr" average column over everything.
     * {@code data-col} indices stay globally continuous across all three blocks (undated -> H1 ->
     * H2, "Jahr" contributes none since it's only an average column, no repeated cells) so the
     * existing keyboard nav/autosave in {@code notenfuchs.js} keeps working unmodified.
     */
    private HalfYearGridData loadHalfYearGridData(Subject subject, LocalDate cutoff) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<Student> allStudents = Student.list("schoolClass.id = ?1 order by name", subject.schoolClass.id);
        List<Student> students = allStudents.stream().filter(s -> !s.hasLeft()).toList();
        List<Student> leftStudents = allStudents.stream().filter(Student::hasLeft).toList();

        List<CategoryColumns> allColumns = new ArrayList<>();
        List<Assessment> allAssessments = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            allColumns.add(new CategoryColumns(category, assessments));
            allAssessments.addAll(assessments);
        }

        HalfSplit split = splitByHalf(allColumns, cutoff);

        Map<Long, List<PointsGradeBandData>> bandsByAssessment = bandsByAssessment(allAssessments);
        Map<GradeKey, Grade> grades = gradesByKey(Grade.list("assessment.category.subject.id", subject.id));

        HalfYearBuild active = buildHalfYearRows(split, allColumns, students, subject, bandsByAssessment, grades);
        List<HalfYearRowView> leftRows =
                buildHalfYearRows(split, allColumns, leftStudents, subject, bandsByAssessment, grades).rows();
        BlockBuild undatedBuild = active.undated();
        BlockBuild h1Build = active.h1();
        BlockBuild h2Build = active.h2();
        List<HalfYearRowView> rows = active.rows();

        int undatedTotalCols = totalColumns(split.undated());
        int h1TotalCols = totalColumns(split.h1());
        int h2TotalCols = totalColumns(split.h2());
        int maxCol = Math.max(0, h2Build.nextColIndex() - 1);
        int maxRow = Math.max(0, students.size() - 1);
        boolean gridEmpty = allColumns.isEmpty() || allAssessments.isEmpty();

        return new HalfYearGridData(split.undated(), undatedTotalCols, split.h1(), h1TotalCols + 1,
                split.h2(), h2TotalCols + 1, students, rows, leftRows,
                undatedBuild.footers(), h1Build.footers(), h2Build.footers(), maxCol, maxRow, gridEmpty);
    }

    private record HalfYearBuild(List<HalfYearRowView> rows, BlockBuild undated, BlockBuild h1, BlockBuild h2) {
    }

    private HalfYearBuild buildHalfYearRows(HalfSplit split, List<CategoryColumns> allColumns, List<Student> students,
                                            Subject subject, Map<Long, List<PointsGradeBandData>> bandsByAssessment,
                                            Map<GradeKey, Grade> grades) {
        BlockBuild undatedBuild = buildBlockCells(split.undated(), students, subject, bandsByAssessment, grades, 0);
        BlockBuild h1Build = buildBlockCells(split.h1(), students, subject, bandsByAssessment, grades, undatedBuild.nextColIndex());
        BlockBuild h2Build = buildBlockCells(split.h2(), students, subject, bandsByAssessment, grades, h1Build.nextColIndex());

        List<SubjectAverageResult> h1Averages = subjectAveragesForBlock(split.h1(), students, subject, grades);
        List<SubjectAverageResult> h2Averages = subjectAveragesForBlock(split.h2(), students, subject, grades);
        List<SubjectAverageResult> jahrAverages = subjectAveragesForBlock(allColumns, students, subject, grades);
        Map<Long, BigDecimal> h1ReportGrades = ReportGrade.bySubject(subject.id, ReportGrade.Period.FIRST_HALF);
        Map<Long, BigDecimal> jahrReportGrades = ReportGrade.bySubject(subject.id, ReportGrade.Period.YEAR);

        List<HalfYearRowView> rows = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            Student student = students.get(i);
            SubjectAverageResult h1Avg = h1Averages.get(i);
            SubjectAverageResult h2Avg = h2Averages.get(i);
            SubjectAverageResult jahrAvg = jahrAverages.get(i);
            rows.add(new HalfYearRowView(i, new StudentView(student.id, student.effectiveName()),
                    undatedBuild.cellsByStudent().get(i),
                    h1Build.cellsByStudent().get(i), h1Avg.rawAverage(), h1Avg.finalGrade(),
                    displayLabel(subject, h1Avg), formatOrNull(subject.gradeScale, h1ReportGrades.get(student.id)),
                    h2Build.cellsByStudent().get(i), h2Avg.rawAverage(), h2Avg.finalGrade(),
                    displayLabel(subject, h2Avg),
                    jahrAvg.rawAverage(), jahrAvg.finalGrade(),
                    formatOrNull(subject.gradeScale, jahrReportGrades.get(student.id))));
        }
        return new HalfYearBuild(rows, undatedBuild, h1Build, h2Build);
    }

    private static int totalColumns(List<CategoryColumns> columns) {
        return columns.stream().mapToInt(CategoryColumns::columnCount).sum();
    }

    /**
     * Splits each category's Assessments into undated/H1/H2 subsets via
     * {@link HalfYearAssessmentPartitioner}. {@code undated} comes back as an empty list
     * (dropping every category, not just emptying their Assessment lists) unless at least one
     * category actually has an undated Assessment - see the "Ohne Datum" block's "only rendered
     * if undated Assessments exist" rule.
     */
    HalfSplit splitByHalf(List<CategoryColumns> allColumns, LocalDate cutoff) {
        List<CategoryColumns> undated = new ArrayList<>();
        List<CategoryColumns> h1 = new ArrayList<>();
        List<CategoryColumns> h2 = new ArrayList<>();
        boolean hasUndated = false;
        for (CategoryColumns cc : allColumns) {
            List<Assessment> undatedAssessments = new ArrayList<>();
            List<Assessment> h1Assessments = new ArrayList<>();
            List<Assessment> h2Assessments = new ArrayList<>();
            for (Assessment assessment : cc.assessments()) {
                switch (halfYearPartitioner.classify(assessment.date, cutoff)) {
                    case UNDATED -> undatedAssessments.add(assessment);
                    case FIRST -> h1Assessments.add(assessment);
                    case SECOND -> h2Assessments.add(assessment);
                }
            }
            if (!undatedAssessments.isEmpty()) {
                hasUndated = true;
            }
            undated.add(new CategoryColumns(cc.category(), undatedAssessments));
            h1.add(new CategoryColumns(cc.category(), h1Assessments));
            h2.add(new CategoryColumns(cc.category(), h2Assessments));
        }
        return new HalfSplit(hasUndated ? undated : List.of(), h1, h2);
    }

    /**
     * Renders one column block's cells for every student - the same per-cell logic as
     * {@link #loadGridData}'s body-row loop (points-based display/derivation, placeholder
     * columns for an empty category) plus that block's own per-assessment footer averages, but
     * factored out so it can run three times (undated/H1/H2) with {@code data-col} continuing
     * on from the previous block via {@code startColIndex}.
     */
    private BlockBuild buildBlockCells(List<CategoryColumns> blockColumns, List<Student> students, Subject subject,
                                        Map<Long, List<PointsGradeBandData>> bandsByAssessment,
                                        Map<GradeKey, Grade> grades, int startColIndex) {
        int totalCols = totalColumns(blockColumns);
        LocalDate today = LocalDate.now();
        List<List<BigDecimal>> columnValues = new ArrayList<>();
        for (int i = 0; i < totalCols; i++) {
            columnValues.add(new ArrayList<>());
        }

        List<List<CellView>> cellsByStudent = new ArrayList<>();
        for (Student student : students) {
            List<CellView> cells = new ArrayList<>();
            int colIndex = startColIndex;
            int localIndex = 0;
            for (CategoryColumns cc : blockColumns) {
                if (cc.assessments().isEmpty()) {
                    cells.add(new CellView(colIndex, null, true, null, null, null, false, null, false, false));
                    colIndex++;
                    continue;
                }
                boolean categoryStart = true;
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = grades.get(new GradeKey(assessment.id, student.id));
                    String displayValue = null;
                    String derivedDisplay = null;
                    BigDecimal gradeValue = null;
                    if (assessment.pointsBased) {
                        if (grade != null && grade.points != null) {
                            displayValue = display(grade.points);
                            gradeValue = pointsConversionService.convert(grade.points,
                                    bandsByAssessment.get(assessment.id), subject.gradeScale, assessment.roundingMode);
                            derivedDisplay = display(gradeValue);
                        }
                    } else if (grade != null && grade.value != null) {
                        displayValue = subject.gradeScale.format(grade.value);
                        gradeValue = grade.value;
                    }
                    if (gradeValue != null) {
                        columnValues.get(localIndex).add(gradeValue);
                    }
                    Absence absence = grade != null ? grade.absence : null;
                    boolean due = OpenGrades.isDue(assessment, today);
                    cells.add(new CellView(colIndex, assessment.id, categoryStart, displayValue, gradeValue, absence,
                            assessment.pointsBased, derivedDisplay, due, due && grade == null));
                    categoryStart = false;
                    colIndex++;
                    localIndex++;
                }
            }
            cellsByStudent.add(cells);
        }

        List<CategoryFooter> footers = new ArrayList<>();
        int footerColIndex = 0;
        for (CategoryColumns cc : blockColumns) {
            List<ColumnAverageView> columnAverages = new ArrayList<>();
            if (cc.assessments().isEmpty()) {
                columnAverages.add(new ColumnAverageView(null, null, null));
            } else {
                for (Assessment assessment : cc.assessments()) {
                    List<BigDecimal> values = columnValues.get(footerColIndex);
                    SubjectAverageResult columnAverage = gradeService.calculateAssessmentAverage(
                            values, subject.gradeScale, subject.roundingMode);
                    columnAverages.add(new ColumnAverageView(assessment.id,
                            columnAverage.rawAverage(), columnAverage.finalGrade()));
                    footerColIndex++;
                }
            }
            footers.add(new CategoryFooter(cc.category(), columnAverages));
        }

        return new BlockBuild(cellsByStudent, footers, startColIndex + totalCols);
    }

    /**
     * Computes one {@link SubjectAverageResult} per student by calling
     * {@link GradeService#calculateSubjectAverage} exactly as {@link #averagePayload} already
     * does - the only difference between a "Jahr" average and an "H1"/"H2" average is which
     * {@link CategoryColumns} subset gets passed in here. Empty-category normalization (a
     * category with none of this block's Assessments graded for a student is excluded, not
     * treated as a zero) is entirely {@link GradeService}'s existing behavior - untouched.
     */
    List<SubjectAverageResult> subjectAveragesForBlock(List<CategoryColumns> blockColumns,
                                                                 List<Student> students, Subject subject,
                                                                 Map<GradeKey, Grade> grades) {
        List<SubjectAverageResult> result = new ArrayList<>();
        for (Student student : students) {
            List<CategoryData> categoryDataList = new ArrayList<>();
            for (CategoryColumns cc : blockColumns) {
                List<GradeData> gradeDataList = new ArrayList<>();
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = grades.get(new GradeKey(assessment.id, student.id));
                    BigDecimal effectiveValue = grade != null
                            ? effectiveGradeValue(grade, assessment, subject.gradeScale) : null;
                    if (effectiveValue != null) {
                        gradeDataList.add(new GradeData(effectiveValue, assessment.factor));
                    }
                }
                categoryDataList.add(new CategoryData(cc.category().weightPercent, gradeDataList));
            }
            result.add(gradeService.calculateSubjectAverage(categoryDataList, subject.gradeScale, subject.roundingMode));
        }
        return result;
    }

    /**
     * Formats one H1/H2 average per {@link de.notenfuchs.domain.SchoolClass#halfYearGradeDisplay}
     * - "Jahr" never goes through this, it always stays the plain {@code finalGrade} (see
     * {@link HalfYearGradeDisplayService}).
     */
    String displayLabel(Subject subject, SubjectAverageResult average) {
        return halfYearGradeDisplayService.label(average.rawAverage(), average.finalGrade(),
                subject.schoolClass.halfYearGradeDisplay, subject.schoolClass.halfYearTendencyThreshold,
                subject.gradeScale);
    }

    @POST
    @Path("/cell")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveCell(@PathParam("id") Long id,
                              @FormParam("studentId") Long studentId,
                              @FormParam("assessmentId") Long assessmentId,
                              @FormParam("value") String rawValue) {
        String currentSubject = currentUser.effectiveSubject();
        Subject subject = guard.requireTeachesSubject(id, currentSubject);
        Student student = guard.requireClassAccessStudent(studentId, currentSubject);
        Assessment assessment = guard.requireTeachesAssessment(assessmentId, currentSubject);
        if (!assessment.category.subject.id.equals(subject.id) || !student.schoolClass.id.equals(subject.schoolClass.id)) {
            throw new NotFoundException();
        }
        if (student.hasLeft()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Schüler ist ausgetreten").build();
        }

        Grade existing = Grade.find("assessment.id = ?1 and student.id = ?2", assessmentId, studentId).firstResult();

        String trimmed = rawValue == null ? "" : rawValue.trim();
        // An absence letter may stand alone or next to the number, before or after it.
        Absence absence = null;
        if (!trimmed.isEmpty() && Absence.fromCode(trimmed.substring(trimmed.length() - 1)) != null) {
            absence = Absence.fromCode(trimmed.substring(trimmed.length() - 1));
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        } else if (!trimmed.isEmpty() && Absence.fromCode(trimmed.substring(0, 1)) != null) {
            absence = Absence.fromCode(trimmed.substring(0, 1));
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            if (absence == null) {
                if (existing != null) {
                    existing.delete();
                }
            } else {
                Grade grade = existing != null ? existing : newGrade(assessment, student);
                grade.value = null;
                grade.points = null;
                grade.absence = absence;
                if (existing == null) {
                    grade.persist();
                }
            }
            return Response.ok(averagePayload(subject, student, assessment, null, absence)).build();
        }

        BigDecimal enteredValue;
        try {
            enteredValue = assessment.pointsBased
                    ? new BigDecimal(trimmed.replace(",", "."))
                    : subject.gradeScale.parseGrade(trimmed);
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(assessment.pointsBased ? "Ungültiger Punktwert: '" + rawValue + "'"
                            : "Ungültiger Notenwert: '" + rawValue + "'")
                    .build();
        }

        if (assessment.pointsBased) {
            if (enteredValue.compareTo(BigDecimal.ZERO) < 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Punkte dürfen nicht negativ sein").build();
            }
            if (existing != null) {
                existing.points = enteredValue;
                existing.value = null;
                existing.absence = absence;
            } else {
                Grade grade = newGrade(assessment, student);
                grade.points = enteredValue;
                grade.absence = absence;
                grade.persist();
            }
        } else {
            if (!subject.gradeScale.contains(enteredValue)) {
                return outOfScale(subject.gradeScale);
            }
            if (existing != null) {
                existing.value = enteredValue;
                existing.points = null;
                existing.absence = absence;
            } else {
                Grade grade = newGrade(assessment, student);
                grade.value = enteredValue;
                grade.absence = absence;
                grade.persist();
            }
        }

        return Response.ok(averagePayload(subject, student, assessment, enteredValue, absence)).build();
    }

    static Response outOfScale(GradeScale scale) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("Note muss zwischen " + display(scale.min) + " und " + display(scale.max) + " liegen")
                .build();
    }

    private static Grade newGrade(Assessment assessment, Student student) {
        Grade grade = new Grade();
        grade.assessment = assessment;
        grade.student = student;
        return grade;
    }

    /**
     * The subject-wide ("Jahr") average is always recomputed regardless of the grid's display
     * mode - unaffected by Halbjahr. When the class has a {@code halfYearCutoff}, the edited
     * student's H1/H2 averages are recomputed too (via the same {@link #subjectAveragesForBlock}
     * that {@link #loadHalfYearGridData} uses), so both half-columns stay live without a reload -
     * see {@code updateAverageRow}'s {@code data-scope} handling in {@code notenfuchs.js}.
     */
    private CellSaveResponse averagePayload(Subject subject, Student student, Assessment assessment,
                                             BigDecimal enteredValue, Absence absence) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<CategoryColumns> allColumns = new ArrayList<>();
        for (GradeCategory category : categories) {
            allColumns.add(new CategoryColumns(category, Assessment.list("category.id", category.id)));
        }
        List<Student> singleStudent = List.of(student);
        Map<GradeKey, Grade> grades = gradesByKey(
                Grade.list("student.id = ?1 and assessment.category.subject.id = ?2", student.id, subject.id));
        SubjectAverageResult average = subjectAveragesForBlock(allColumns, singleStudent, subject, grades).get(0);

        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        SubjectAverageResult h1Average = null;
        SubjectAverageResult h2Average = null;
        if (cutoff != null) {
            HalfSplit split = splitByHalf(allColumns, cutoff);
            h1Average = subjectAveragesForBlock(split.h1(), singleStudent, subject, grades).get(0);
            h2Average = subjectAveragesForBlock(split.h2(), singleStudent, subject, grades).get(0);
        }

        List<Grade> assessmentGrades = Grade.list("assessment.id = ?1 and student.leftOn is null", assessment.id);
        List<BigDecimal> assessmentValues = assessmentGrades.stream()
                .map(g -> effectiveGradeValue(g, assessment, subject.gradeScale))
                .filter(java.util.Objects::nonNull)
                .toList();
        SubjectAverageResult assessmentAverage = gradeService.calculateAssessmentAverage(
                assessmentValues, subject.gradeScale, subject.roundingMode);

        String number = enteredValue == null ? null
                : assessment.pointsBased ? display(enteredValue) : subject.gradeScale.format(enteredValue);
        String displayValue = cellText(number, absence);
        String derivedGradeDisplay = null;
        if (assessment.pointsBased && enteredValue != null) {
            BigDecimal derived = pointsConversionService.convert(enteredValue,
                    bandData(assessment), subject.gradeScale, assessment.roundingMode);
            derivedGradeDisplay = display(derived);
        }
        String rawAverageStr = average.rawAverage() != null ? display(average.rawAverage()) : null;
        String assessmentRawAverageStr = assessmentAverage.rawAverage() != null
                ? display(assessmentAverage.rawAverage()) : null;
        String h1RawAverageStr = h1Average != null && h1Average.rawAverage() != null
                ? display(h1Average.rawAverage()) : null;
        String h2RawAverageStr = h2Average != null && h2Average.rawAverage() != null
                ? display(h2Average.rawAverage()) : null;
        String h1DisplayLabel = h1Average != null ? displayLabel(subject, h1Average) : null;
        String h2DisplayLabel = h2Average != null ? displayLabel(subject, h2Average) : null;
        return new CellSaveResponse(displayValue, rawAverageStr, average.finalGrade(),
                assessment.id, assessmentRawAverageStr, assessmentAverage.finalGrade(), derivedGradeDisplay,
                h1RawAverageStr, h1Average != null ? h1Average.finalGrade() : null, h1DisplayLabel,
                h2RawAverageStr, h2Average != null ? h2Average.finalGrade() : null, h2DisplayLabel);
    }

    /**
     * Resolves the grade value a {@link Grade} row actually contributes to averages: the
     * directly-entered {@link Grade#value} for a normal assessment, or a live conversion of
     * {@link Grade#points} via {@link PointsConversionService} for a points-based one - never a
     * stored/frozen number, so editing the points or the bands is reflected immediately.
     */
    BigDecimal effectiveGradeValue(Grade grade, Assessment assessment, GradeScale scale) {
        if (assessment.pointsBased) {
            if (grade.points == null) {
                return null;
            }
            return pointsConversionService.convert(grade.points, bandData(assessment), scale, assessment.roundingMode);
        }
        return grade.value;
    }

    private List<PointsGradeBandData> bandData(Assessment assessment) {
        List<PointsGradeBand> bands = PointsGradeBand.list("assessment.id", assessment.id);
        return bands.stream().map(b -> new PointsGradeBandData(b.points, b.gradeValue)).toList();
    }

    Map<Long, List<PointsGradeBandData>> bandsByAssessment(List<Assessment> assessments) {
        Map<Long, List<PointsGradeBandData>> byAssessment = new HashMap<>();
        for (Assessment assessment : assessments) {
            if (assessment.pointsBased) {
                byAssessment.put(assessment.id, bandData(assessment));
            }
        }
        return byAssessment;
    }

    /**
     * Indexes a subject's {@link Grade}s by (assessment, student) so {@link #buildBlockCells} and
     * {@link #subjectAveragesForBlock} can look a student's grade up in memory instead of issuing
     * one {@code Grade.find} per cell - both the HTML grid and the Halbjahr split view otherwise
     * re-query the same rows once for the cell display and again for the average calculation.
     */
    static Map<GradeKey, Grade> gradesByKey(List<Grade> grades) {
        Map<GradeKey, Grade> byKey = new HashMap<>();
        for (Grade grade : grades) {
            byKey.put(new GradeKey(grade.assessment.id, grade.student.id), grade);
        }
        return byKey;
    }

    static String formatOrNull(GradeScale scale, BigDecimal value) {
        return value != null ? scale.format(value) : null;
    }

    /**
     * Renders the same grid data used by {@link #grid(Long)} as an .xlsx workbook. The column
     * layout mirrors the HTML table exactly: {@code row.cells()} and each
     * {@code categoryFooters} entry's {@code columnAverages()} are already in left-to-right
     * column order (one entry per rendered column, including the single placeholder slot for
     * a category with no Leistungen yet - see the placeholder-column note in
     * {@link #grid(Long)}), so a plain list index maps 1:1 to a sheet column.
     *
     * <p>For a points-based cell, the exported number is the derived grade (not the raw
     * points) - the same value the average columns already use - so the exported sheet stays
     * numerically consistent for further spreadsheet calculations.
     */
    private byte[] buildWorkbook(Subject subject, GridData data) {
        return toBytes(workbook -> writeSheet(workbook, WorkbookUtil.createSafeSheetName(subject.name), subject, data));
    }

    private void writeSheet(XSSFWorkbook workbook, String sheetName, Subject subject, GridData data) {
        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle headerStyle = headerStyle(workbook);

        int assessmentColumnCount = data.categoryColumns().stream()
                .mapToInt(CategoryColumns::columnCount).sum();
        int studentCol = 0;
        int avgRawCol = 1 + assessmentColumnCount;
        int avgFinalCol = avgRawCol + 1;
        int reportCol = avgFinalCol + 1;

        Row headerRow1 = sheet.createRow(0);
        Row headerRow2 = sheet.createRow(1);

        setHeaderCell(headerRow1, studentCol, "Schüler", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, studentCol, studentCol));

        int col = 1;
        for (CategoryColumns cc : data.categoryColumns()) {
            setHeaderCell(headerRow1, col, cc.category().name + " (" + display(cc.category().weightPercent) + " %)",
                    headerStyle);
            int span = cc.columnCount();
            if (span > 1) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, col, col + span - 1));
            }
            if (cc.assessments().isEmpty()) {
                setHeaderCell(headerRow2, col, "noch keine Leistungen", headerStyle);
            } else {
                int i = 0;
                for (Assessment assessment : cc.assessments()) {
                    setHeaderCell(headerRow2, col + i,
                            assessment.name + " (Faktor " + display(assessment.factor) + ")", headerStyle);
                    i++;
                }
            }
            col += span;
        }

        setHeaderCell(headerRow1, avgRawCol, "Ø", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, avgRawCol, avgRawCol));
        setHeaderCell(headerRow1, avgFinalCol, "Note", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, avgFinalCol, avgFinalCol));
        setHeaderCell(headerRow1, reportCol, "Zeugnisnote", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, reportCol, reportCol));

        int rowNum = 2;
        // Students who left come last, marked, since their grades may still be needed for a report.
        List<RowView> exportRows = new ArrayList<>(data.rows());
        exportRows.addAll(data.leftRows());
        for (RowView row : exportRows) {
            Row xlsxRow = sheet.createRow(rowNum++);
            xlsxRow.createCell(studentCol).setCellValue(exportName(row.student(), data.leftRows().contains(row)));
            List<CellView> cells = row.cells();
            for (int i = 0; i < cells.size(); i++) {
                CellView cell = cells.get(i);
                writeCell(xlsxRow, 1 + i, cell);
            }
            if (row.rawAverage() != null) {
                xlsxRow.createCell(avgRawCol).setCellValue(row.rawAverage().doubleValue());
            }
            if (row.finalGrade() != null) {
                xlsxRow.createCell(avgFinalCol).setCellValue(row.finalGrade());
            }
            writeLabel(xlsxRow, reportCol, reportGradeOrComputed(row.reportGrade(), row.finalGrade()));
        }

        Row footerRow = sheet.createRow(rowNum);
        footerRow.createCell(studentCol).setCellValue("Ø je Leistung");
        int footerCol = 1;
        for (CategoryFooter cf : data.categoryFooters()) {
            for (ColumnAverageView columnAverage : cf.columnAverages()) {
                if (columnAverage.rawAverage() != null) {
                    footerRow.createCell(footerCol).setCellValue(columnAverage.rawAverage().doubleValue());
                }
                footerCol++;
            }
        }

        sheet.createFreezePane(1, 2);
        for (int c = 0; c <= reportCol; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /**
     * Mirrors {@link #buildWorkbook}, but with the same block structure the HTML grid renders
     * once a Halbjahr cutoff is set (see {@link #loadHalfYearGridData}): Student | [Ohne Datum,
     * if any] | [1. Halbjahr + its own Ø/Note] | [2. Halbjahr + its own Ø/Note] | Jahr Ø/Note,
     * with a third header row for the region labels ("Ohne Datum"/"1. Halbjahr"/"2.
     * Halbjahr"/"Jahr") above the existing category/assessment header rows.
     */
    private byte[] buildHalfYearWorkbook(Subject subject, HalfYearGridData data) {
        return toBytes(workbook -> writeHalfYearSheet(workbook, WorkbookUtil.createSafeSheetName(subject.name), subject, data));
    }

    private void writeHalfYearSheet(XSSFWorkbook workbook, String sheetName, Subject subject, HalfYearGridData data) {
        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle headerStyle = headerStyle(workbook);

        int studentCol = 0;
        int col = 1;
        int undatedStartCol = col;
        col += data.undatedTotalCols();
        int h1StartCol = col;
        col += totalColumns(data.h1Columns());
        int h1AvgRawCol = col++;
        int h1AvgFinalCol = col++;
        int h2StartCol = col;
        col += totalColumns(data.h2Columns());
        int h2AvgRawCol = col++;
        int h2AvgFinalCol = col++;
        int jahrAvgRawCol = col++;
        int jahrAvgFinalCol = col++;
        int h1ReportCol = col++;
        int jahrReportCol = col++;
        int lastCol = jahrReportCol;

        Row regionRow = sheet.createRow(0);
        Row categoryRow = sheet.createRow(1);
        Row assessmentRow = sheet.createRow(2);

        setHeaderCell(regionRow, studentCol, "Schüler", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 2, studentCol, studentCol));

        if (data.undatedTotalCols() > 0) {
            setHeaderCell(regionRow, undatedStartCol, "Ohne Datum (zählt nur in die Jahresnote)", headerStyle);
            if (data.undatedTotalCols() > 1) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, undatedStartCol,
                        undatedStartCol + data.undatedTotalCols() - 1));
            }
            writeCategoryAndAssessmentHeaders(sheet, categoryRow, assessmentRow, data.undatedColumns(),
                    undatedStartCol, headerStyle);
        }

        setHeaderCell(regionRow, h1StartCol, "1. Halbjahr", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, h1StartCol, h1AvgFinalCol));
        writeCategoryAndAssessmentHeaders(sheet, categoryRow, assessmentRow, data.h1Columns(), h1StartCol, headerStyle);
        writeAverageHeader(sheet, categoryRow, h1AvgRawCol, h1AvgFinalCol, headerStyle);

        setHeaderCell(regionRow, h2StartCol, "2. Halbjahr", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, h2StartCol, h2AvgFinalCol));
        writeCategoryAndAssessmentHeaders(sheet, categoryRow, assessmentRow, data.h2Columns(), h2StartCol, headerStyle);
        writeAverageHeader(sheet, categoryRow, h2AvgRawCol, h2AvgFinalCol, headerStyle);

        setHeaderCell(regionRow, jahrAvgRawCol, "Jahr", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, jahrAvgRawCol, jahrAvgFinalCol));
        writeAverageHeader(sheet, categoryRow, jahrAvgRawCol, jahrAvgFinalCol, headerStyle);

        setHeaderCell(regionRow, h1ReportCol, "Zeugnisnote", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, h1ReportCol, jahrReportCol));
        setHeaderCell(categoryRow, h1ReportCol, "1. Halbjahr", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 2, h1ReportCol, h1ReportCol));
        setHeaderCell(categoryRow, jahrReportCol, "Jahr", headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 2, jahrReportCol, jahrReportCol));

        int rowNum = 3;
        List<HalfYearRowView> exportRows = new ArrayList<>(data.rows());
        exportRows.addAll(data.leftRows());
        for (HalfYearRowView row : exportRows) {
            Row xlsxRow = sheet.createRow(rowNum++);
            xlsxRow.createCell(studentCol).setCellValue(exportName(row.student(), data.leftRows().contains(row)));
            writeCells(xlsxRow, row.undatedCells(), undatedStartCol);
            writeCells(xlsxRow, row.h1Cells(), h1StartCol);
            writeAverageWithLabel(xlsxRow, h1AvgRawCol, h1AvgFinalCol, row.h1RawAverage(), row.h1DisplayLabel());
            writeCells(xlsxRow, row.h2Cells(), h2StartCol);
            writeAverageWithLabel(xlsxRow, h2AvgRawCol, h2AvgFinalCol, row.h2RawAverage(), row.h2DisplayLabel());
            writeAverage(xlsxRow, jahrAvgRawCol, jahrAvgFinalCol, row.jahrRawAverage(), row.jahrFinalGrade());
            writeLabel(xlsxRow, h1ReportCol, row.h1ReportGrade() != null ? row.h1ReportGrade() : row.h1DisplayLabel());
            writeLabel(xlsxRow, jahrReportCol, reportGradeOrComputed(row.jahrReportGrade(), row.jahrFinalGrade()));
        }

        Row footerRow = sheet.createRow(rowNum);
        footerRow.createCell(studentCol).setCellValue("Ø je Leistung");
        writeFooterAverages(footerRow, data.undatedFooters(), undatedStartCol);
        writeFooterAverages(footerRow, data.h1Footers(), h1StartCol);
        writeFooterAverages(footerRow, data.h2Footers(), h2StartCol);

        sheet.createFreezePane(1, 3);
        for (int c = 0; c <= lastCol; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /**
     * Adds this Fach's grade sheet (the same content as its own export) to a workbook, e.g. the
     * class-wide export. The caller must already have checked that the viewer teaches the Fach.
     */
    void addSubjectSheet(XSSFWorkbook workbook, String sheetName, Subject subject) {
        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        if (cutoff == null) {
            writeSheet(workbook, sheetName, subject, loadGridData(subject));
        } else {
            writeHalfYearSheet(workbook, sheetName, subject, loadHalfYearGridData(subject, cutoff));
        }
    }

    static byte[] toBytes(java.util.function.Consumer<XSSFWorkbook> fill) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            fill.accept(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes one block's category (row 1, colspan-merged) and assessment (row 2) headers, starting at {@code startCol}. */
    private void writeCategoryAndAssessmentHeaders(Sheet sheet, Row categoryRow, Row assessmentRow,
                                                     List<CategoryColumns> columns, int startCol, CellStyle style) {
        int col = startCol;
        for (CategoryColumns cc : columns) {
            setHeaderCell(categoryRow, col, cc.category().name + " (" + display(cc.category().weightPercent) + " %)", style);
            int span = cc.columnCount();
            if (span > 1) {
                sheet.addMergedRegion(new CellRangeAddress(1, 1, col, col + span - 1));
            }
            if (cc.assessments().isEmpty()) {
                setHeaderCell(assessmentRow, col, "noch keine Leistungen", style);
            } else {
                int i = 0;
                for (Assessment assessment : cc.assessments()) {
                    setHeaderCell(assessmentRow, col + i,
                            assessment.name + " (Faktor " + display(assessment.factor) + ")", style);
                    i++;
                }
            }
            col += span;
        }
    }

    /** A block's own "Ø"/"Note" pair, rowspan-merged across the category+assessment header rows. */
    private void writeAverageHeader(Sheet sheet, Row categoryRow, int rawCol, int finalCol, CellStyle style) {
        setHeaderCell(categoryRow, rawCol, "Ø", style);
        sheet.addMergedRegion(new CellRangeAddress(1, 2, rawCol, rawCol));
        setHeaderCell(categoryRow, finalCol, "Note", style);
        sheet.addMergedRegion(new CellRangeAddress(1, 2, finalCol, finalCol));
    }

    private void writeCells(Row xlsxRow, List<CellView> cells, int startCol) {
        for (int i = 0; i < cells.size(); i++) {
            writeCell(xlsxRow, startCol + i, cells.get(i));
        }
    }

    /** A grade stays a number so Excel can calculate with it; an absence without a grade becomes its letter. */
    private static void writeCell(Row xlsxRow, int col, CellView cell) {
        if (cell.gradeValue() != null) {
            xlsxRow.createCell(col).setCellValue(cell.gradeValue().doubleValue());
        } else if (cell.absence() != null) {
            xlsxRow.createCell(col).setCellValue(cell.absence().code);
        }
    }

    private void writeAverage(Row xlsxRow, int rawCol, int finalCol, BigDecimal rawAverage, Integer finalGrade) {
        if (rawAverage != null) {
            xlsxRow.createCell(rawCol).setCellValue(rawAverage.doubleValue());
        }
        if (finalGrade != null) {
            xlsxRow.createCell(finalCol).setCellValue(finalGrade);
        }
    }

    /**
     * Same as {@link #writeAverage}, but for an H1/H2 "Note" column: the label is a plain whole
     * number in the default {@code WHOLE}-without-tendency configuration (written as a numeric
     * cell, exactly like {@link #writeAverage}, so existing spreadsheets keep working), but falls
     * back to a text cell once it's a half-grade or carries a +/- tendency suffix that a numeric
     * cell can't represent.
     */
    private void writeAverageWithLabel(Row xlsxRow, int rawCol, int finalCol, BigDecimal rawAverage, String displayLabel) {
        if (rawAverage != null) {
            xlsxRow.createCell(rawCol).setCellValue(rawAverage.doubleValue());
        }
        writeLabel(xlsxRow, finalCol, displayLabel);
    }

    private static void writeLabel(Row xlsxRow, int col, String label) {
        if (label != null) {
            Cell cell = xlsxRow.createCell(col);
            try {
                cell.setCellValue(new BigDecimal(label).doubleValue());
            } catch (NumberFormatException e) {
                cell.setCellValue(label);
            }
        }
    }

    private static String reportGradeOrComputed(String reportGrade, Integer finalGrade) {
        if (reportGrade != null) {
            return reportGrade;
        }
        return finalGrade != null ? finalGrade.toString() : null;
    }

    private void writeFooterAverages(Row footerRow, List<CategoryFooter> footers, int startCol) {
        int col = startCol;
        for (CategoryFooter cf : footers) {
            for (ColumnAverageView columnAverage : cf.columnAverages()) {
                if (columnAverage.rawAverage() != null) {
                    footerRow.createCell(col).setCellValue(columnAverage.rawAverage().doubleValue());
                }
                col++;
            }
        }
    }

    private static String exportName(StudentView student, boolean left) {
        return left ? student.effectiveName() + " (ausgetreten)" : student.effectiveName();
    }

    private static void setHeaderCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(boldFont);
        return style;
    }

    // ---- View models for the Qute template (and the .xlsx export, which reuses them) ----

    /** Lookup key for {@link #gradesByKey}, indexing a subject's grades by (assessment, student). */
    record GradeKey(Long assessmentId, Long studentId) {
    }

    /** Everything needed to render either the HTML grid or the .xlsx export - see {@link #loadGridData(Subject)}. */
    private record GridData(List<CategoryColumns> categoryColumns, List<Student> students, List<RowView> rows,
                             List<RowView> leftRows, List<CategoryFooter> categoryFooters, int maxCol, int maxRow,
                             boolean gridEmpty) {
    }

    public record CategoryColumns(GradeCategory category, List<Assessment> assessments) {
        /** At least 1, even for a category with no Leistungen yet - see the placeholder-column note in {@link #grid(Long)}. */
        public int columnCount() {
            return Math.max(1, assessments.size());
        }
    }

    public record StudentView(Long id, String effectiveName) {
    }

    /**
     * {@code displayValue} is the grade as typed ("2-") or the points, without the absence letter that
     * {@link #inputValue} adds; {@code gradeValue} is the grade that counts (derived for points), for the xlsx.
     * {@code derivedGradeDisplay} is only set for points: the grade from the Notenschlüssel.
     */
    public record CellView(int colIndex, Long assessmentId, boolean categoryStart, String displayValue,
                            BigDecimal gradeValue, Absence absence,
                            boolean pointsBased, String derivedGradeDisplay, boolean due, boolean open) {
        public String inputValue() {
            return cellText(displayValue, absence);
        }
    }

    /** What a grade cell shows and accepts back: "2-", "2.5", "6 u", "e" or "". */
    static String cellText(String number, Absence absence) {
        if (absence == null) {
            return number != null ? number : "";
        }
        return number != null ? number + " " + absence.code : absence.code;
    }

    /** {@code reportGrade}: the Zeugnisnote set by hand, shown instead of {@code finalGrade}; null if none. */
    public record RowView(int rowIndex, StudentView student, List<CellView> cells,
                           BigDecimal rawAverage, Integer finalGrade, String reportGrade) {
    }

    /** Per-assessment ("Leistung") average across all students, shown in the grid footer. */
    public record ColumnAverageView(Long assessmentId, BigDecimal rawAverage, Integer finalGrade) {
    }

    public record CategoryFooter(GradeCategory category, List<ColumnAverageView> columnAverages) {
    }

    /**
     * @param h1RawAverage/h1FinalGrade/h2RawAverage/h2FinalGrade only set when the class has a
     *                                                            {@code halfYearCutoff} - null
     *                                                            otherwise (the classic single
     *                                                            view has no half columns to
     *                                                            update)
     * @param h1DisplayLabel/h2DisplayLabel what the grid actually renders in the H1/H2 average
     *                                      cell - {@code h1FinalGrade}/{@code h2FinalGrade}
     *                                      optionally decorated with a tendency suffix, or a
     *                                      half-grade, per
     *                                      {@code SchoolClass#halfYearGradeDisplay} (see
     *                                      {@link de.notenfuchs.service.HalfYearGradeDisplayService})
     */
    public record CellSaveResponse(String displayValue, String rawAverage, Integer finalGrade,
                                    Long assessmentId, String assessmentRawAverage, Integer assessmentFinalGrade,
                                    String derivedGradeDisplay,
                                    String h1RawAverage, Integer h1FinalGrade, String h1DisplayLabel,
                                    String h2RawAverage, Integer h2FinalGrade, String h2DisplayLabel) {
    }

    // ---- Halbjahr split view - see loadHalfYearGridData(Subject, LocalDate) ----

    /** Everything needed to render the HTML grid (or .xlsx export) once a Halbjahr cutoff is set. */
    private record HalfYearGridData(List<CategoryColumns> undatedColumns, int undatedTotalCols,
                                     List<CategoryColumns> h1Columns, int h1HeaderColspan,
                                     List<CategoryColumns> h2Columns, int h2HeaderColspan,
                                     List<Student> students, List<HalfYearRowView> rows,
                                     List<HalfYearRowView> leftRows, List<CategoryFooter> undatedFooters, List<CategoryFooter> h1Footers,
                                     List<CategoryFooter> h2Footers, int maxCol, int maxRow, boolean gridEmpty) {
    }

    /** One category's Assessments split three ways by {@link HalfYearAssessmentPartitioner}. */
    record HalfSplit(List<CategoryColumns> undated, List<CategoryColumns> h1, List<CategoryColumns> h2) {
    }

    /** Output of {@link #buildBlockCells}: one column block's cells (per student) and footers. */
    private record BlockBuild(List<List<CellView>> cellsByStudent, List<CategoryFooter> footers, int nextColIndex) {
    }

    /**
     * One student's row across all three Halbjahr blocks plus the Jahr average - {@code jahr*}
     * is the same whole-subject average {@link RowView} would show in the classic single view.
     */
    public record HalfYearRowView(int rowIndex, StudentView student,
                                   List<CellView> undatedCells,
                                   List<CellView> h1Cells, BigDecimal h1RawAverage, Integer h1FinalGrade,
                                   String h1DisplayLabel, String h1ReportGrade,
                                   List<CellView> h2Cells, BigDecimal h2RawAverage, Integer h2FinalGrade,
                                   String h2DisplayLabel,
                                   BigDecimal jahrRawAverage, Integer jahrFinalGrade, String jahrReportGrade) {
    }
}
