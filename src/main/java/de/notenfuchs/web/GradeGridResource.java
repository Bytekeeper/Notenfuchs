package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.GradeScale;
import de.notenfuchs.domain.PointsGradeBand;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
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
    @Location("GridPage/grid.html")
    Template gridTemplate;

    @Inject
    @Location("GridPage/gridHalfYear.html")
    Template gridHalfYearTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grid(@PathParam("id") Long id) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
        LocalDate cutoff = subject.schoolClass.halfYearCutoff;

        if (cutoff == null) {
            GridData data = loadGridData(subject);
            return withUser(gridTemplate
                    .data("subject", subject)
                    .data("categories", data.categoryColumns())
                    .data("students", data.students())
                    .data("rows", data.rows())
                    .data("categoryFooters", data.categoryFooters())
                    .data("maxCol", data.maxCol())
                    .data("maxRow", data.maxRow())
                    .data("gridEmpty", data.gridEmpty()));
        }

        HalfYearGridData data = loadHalfYearGridData(subject, cutoff);
        return withUser(gridHalfYearTemplate
                .data("subject", subject)
                .data("cutoff", cutoff)
                .data("undatedColumns", data.undatedColumns())
                .data("undatedTotalCols", data.undatedTotalCols())
                .data("h1Columns", data.h1Columns())
                .data("h1HeaderColspan", data.h1HeaderColspan())
                .data("h2Columns", data.h2Columns())
                .data("h2HeaderColspan", data.h2HeaderColspan())
                .data("students", data.students())
                .data("rows", data.rows())
                .data("undatedFooters", data.undatedFooters())
                .data("h1Footers", data.h1Footers())
                .data("h2Footers", data.h2Footers())
                .data("maxCol", data.maxCol())
                .data("maxRow", data.maxRow())
                .data("gridEmpty", data.gridEmpty()));
    }

    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportXlsx(@PathParam("id") Long id) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
        LocalDate cutoff = subject.schoolClass.halfYearCutoff;

        byte[] xlsx = cutoff == null
                ? buildWorkbook(subject, loadGridData(subject))
                : buildHalfYearWorkbook(subject, loadHalfYearGridData(subject, cutoff));

        String asciiFilename = sanitizeFilename(subject.name) + "-Noten.xlsx";
        String utf8Filename = URLEncoder.encode(subject.name + "-Noten.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"" + asciiFilename
                        + "\"; filename*=UTF-8''" + utf8Filename)
                .build();
    }

    private GridData loadGridData(Subject subject) {
        Long id = subject.id;
        List<GradeCategory> categories = GradeCategory.list("subject.id", id);
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", subject.schoolClass.id);

        List<CategoryColumns> categoryColumns = new ArrayList<>();
        List<Assessment> allAssessments = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            categoryColumns.add(new CategoryColumns(category, assessments));
            allAssessments.addAll(assessments);
        }

        Map<Long, List<PointsGradeBandData>> bandsByAssessment = new HashMap<>();
        for (Assessment assessment : allAssessments) {
            if (assessment.pointsBased) {
                bandsByAssessment.put(assessment.id, bandData(assessment));
            }
        }

        List<List<BigDecimal>> columnValues = new ArrayList<>();
        for (int i = 0; i < allAssessments.size(); i++) {
            columnValues.add(new ArrayList<>());
        }

        List<RowView> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Student student : students) {
            List<CellView> cells = new ArrayList<>();
            List<CategoryData> categoryDataList = new ArrayList<>();
            int colIndex = 0;
            for (CategoryColumns cc : categoryColumns) {
                if (cc.assessments().isEmpty()) {
                    // A category with no Leistungen yet still needs to occupy exactly one
                    // placeholder column, otherwise its header colspan (0, browser-coerced to 1)
                    // desyncs from the body/footer rows and every column after it - including the
                    // average column - shifts left by one.
                    cells.add(new CellView(colIndex, null, true, null, false, null));
                    categoryDataList.add(new CategoryData(cc.category().weightPercent, List.of()));
                    continue;
                }
                boolean categoryStart = true;
                List<GradeData> gradeDataList = new ArrayList<>();
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                            assessment.id, student.id).firstResult();
                    String displayValue;
                    String derivedDisplay = null;
                    if (assessment.pointsBased) {
                        displayValue = grade != null && grade.points != null ? plain(grade.points) : null;
                        if (grade != null && grade.points != null) {
                            BigDecimal derived = pointsConversionService.convert(grade.points,
                                    bandsByAssessment.get(assessment.id), subject.gradeScale, assessment.roundingMode);
                            derivedDisplay = plain(derived);
                            gradeDataList.add(new GradeData(derived, assessment.factor));
                            columnValues.get(colIndex).add(derived);
                        }
                    } else {
                        displayValue = grade != null ? plain(grade.value) : null;
                        if (grade != null) {
                            gradeDataList.add(new GradeData(grade.value, assessment.factor));
                            columnValues.get(colIndex).add(grade.value);
                        }
                    }
                    cells.add(new CellView(colIndex, assessment.id, categoryStart, displayValue,
                            assessment.pointsBased, derivedDisplay));
                    categoryStart = false;
                    colIndex++;
                }
                categoryDataList.add(new CategoryData(cc.category().weightPercent, gradeDataList));
            }

            SubjectAverageResult average = gradeService.calculateSubjectAverage(
                    categoryDataList, subject.gradeScale, subject.roundingMode);

            rows.add(new RowView(rowIndex, new StudentView(student.id, effectiveName(student)),
                    cells, average.rawAverage(), average.finalGrade()));
            rowIndex++;
        }

        List<CategoryFooter> categoryFooters = new ArrayList<>();
        int footerColIndex = 0;
        for (CategoryColumns cc : categoryColumns) {
            List<ColumnAverageView> columnAverages = new ArrayList<>();
            if (cc.assessments().isEmpty()) {
                // Same placeholder-column reasoning as the body cells above: keep the footer
                // row's column count aligned with the header/body for an empty category.
                columnAverages.add(new ColumnAverageView(null, null, null));
            } else {
                for (Assessment assessment : cc.assessments()) {
                    SubjectAverageResult columnAverage = gradeService.calculateAssessmentAverage(
                            columnValues.get(footerColIndex), subject.gradeScale, subject.roundingMode);
                    columnAverages.add(new ColumnAverageView(assessment.id,
                            columnAverage.rawAverage(), columnAverage.finalGrade()));
                    footerColIndex++;
                }
            }
            categoryFooters.add(new CategoryFooter(cc.category(), columnAverages));
        }

        int maxCol = Math.max(0, allAssessments.size() - 1);
        int maxRow = Math.max(0, rows.size() - 1);
        boolean gridEmpty = categoryColumns.isEmpty() || allAssessments.isEmpty();

        return new GridData(categoryColumns, students, rows, categoryFooters, maxCol, maxRow, gridEmpty);
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
        List<Student> students = Student.list("schoolClass.id = ?1 order by name", subject.schoolClass.id);

        List<CategoryColumns> allColumns = new ArrayList<>();
        List<Assessment> allAssessments = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            allColumns.add(new CategoryColumns(category, assessments));
            allAssessments.addAll(assessments);
        }

        HalfSplit split = splitByHalf(allColumns, cutoff);

        Map<Long, List<PointsGradeBandData>> bandsByAssessment = new HashMap<>();
        for (Assessment assessment : allAssessments) {
            if (assessment.pointsBased) {
                bandsByAssessment.put(assessment.id, bandData(assessment));
            }
        }

        BlockBuild undatedBuild = buildBlockCells(split.undated(), students, subject, bandsByAssessment, 0);
        BlockBuild h1Build = buildBlockCells(split.h1(), students, subject, bandsByAssessment, undatedBuild.nextColIndex());
        BlockBuild h2Build = buildBlockCells(split.h2(), students, subject, bandsByAssessment, h1Build.nextColIndex());

        List<SubjectAverageResult> h1Averages = subjectAveragesForBlock(split.h1(), students, subject);
        List<SubjectAverageResult> h2Averages = subjectAveragesForBlock(split.h2(), students, subject);
        List<SubjectAverageResult> jahrAverages = subjectAveragesForBlock(allColumns, students, subject);

        List<HalfYearRowView> rows = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            Student student = students.get(i);
            SubjectAverageResult h1Avg = h1Averages.get(i);
            SubjectAverageResult h2Avg = h2Averages.get(i);
            SubjectAverageResult jahrAvg = jahrAverages.get(i);
            rows.add(new HalfYearRowView(i, new StudentView(student.id, effectiveName(student)),
                    undatedBuild.cellsByStudent().get(i),
                    h1Build.cellsByStudent().get(i), h1Avg.rawAverage(), h1Avg.finalGrade(),
                    displayLabel(subject, h1Avg),
                    h2Build.cellsByStudent().get(i), h2Avg.rawAverage(), h2Avg.finalGrade(),
                    displayLabel(subject, h2Avg),
                    jahrAvg.rawAverage(), jahrAvg.finalGrade()));
        }

        int undatedTotalCols = totalColumns(split.undated());
        int h1TotalCols = totalColumns(split.h1());
        int h2TotalCols = totalColumns(split.h2());
        int maxCol = Math.max(0, h2Build.nextColIndex() - 1);
        int maxRow = Math.max(0, students.size() - 1);
        boolean gridEmpty = allColumns.isEmpty() || allAssessments.isEmpty();

        return new HalfYearGridData(split.undated(), undatedTotalCols, split.h1(), h1TotalCols + 1,
                split.h2(), h2TotalCols + 1, students, rows,
                undatedBuild.footers(), h1Build.footers(), h2Build.footers(), maxCol, maxRow, gridEmpty);
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
    private HalfSplit splitByHalf(List<CategoryColumns> allColumns, LocalDate cutoff) {
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
                                        Map<Long, List<PointsGradeBandData>> bandsByAssessment, int startColIndex) {
        int totalCols = totalColumns(blockColumns);
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
                    cells.add(new CellView(colIndex, null, true, null, false, null));
                    colIndex++;
                    continue;
                }
                boolean categoryStart = true;
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                            assessment.id, student.id).firstResult();
                    String displayValue;
                    String derivedDisplay = null;
                    if (assessment.pointsBased) {
                        displayValue = grade != null && grade.points != null ? plain(grade.points) : null;
                        if (grade != null && grade.points != null) {
                            BigDecimal derived = pointsConversionService.convert(grade.points,
                                    bandsByAssessment.get(assessment.id), subject.gradeScale, assessment.roundingMode);
                            derivedDisplay = plain(derived);
                            columnValues.get(localIndex).add(derived);
                        }
                    } else {
                        displayValue = grade != null ? plain(grade.value) : null;
                        if (grade != null) {
                            columnValues.get(localIndex).add(grade.value);
                        }
                    }
                    cells.add(new CellView(colIndex, assessment.id, categoryStart, displayValue,
                            assessment.pointsBased, derivedDisplay));
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
                    SubjectAverageResult columnAverage = gradeService.calculateAssessmentAverage(
                            columnValues.get(footerColIndex), subject.gradeScale, subject.roundingMode);
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
    private List<SubjectAverageResult> subjectAveragesForBlock(List<CategoryColumns> blockColumns,
                                                                 List<Student> students, Subject subject) {
        List<SubjectAverageResult> result = new ArrayList<>();
        for (Student student : students) {
            List<CategoryData> categoryDataList = new ArrayList<>();
            for (CategoryColumns cc : blockColumns) {
                List<GradeData> gradeDataList = new ArrayList<>();
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                            assessment.id, student.id).firstResult();
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
    private String displayLabel(Subject subject, SubjectAverageResult average) {
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
        Subject subject = guard.requireOwnedSubject(id, currentSubject);
        Student student = guard.requireOwnedStudent(studentId, currentSubject);
        Assessment assessment = guard.requireOwnedAssessment(assessmentId, currentSubject);

        Grade existing = Grade.find("assessment.id = ?1 and student.id = ?2", assessmentId, studentId).firstResult();

        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.isEmpty()) {
            if (existing != null) {
                existing.delete();
            }
            return Response.ok(averagePayload(subject, student, assessment, null)).build();
        }

        BigDecimal enteredValue;
        try {
            // Accept German comma decimals too, in case a client bypasses the JS normalization.
            enteredValue = new BigDecimal(trimmed.replace(",", "."));
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
            } else {
                Grade grade = new Grade();
                grade.assessment = assessment;
                grade.student = student;
                grade.points = enteredValue;
                grade.persist();
            }
        } else {
            BigDecimal min = subject.gradeScale.min;
            BigDecimal max = subject.gradeScale.max;
            if (enteredValue.compareTo(min) < 0 || enteredValue.compareTo(max) > 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Note muss zwischen " + plain(min) + " und " + plain(max) + " liegen")
                        .build();
            }
            if (existing != null) {
                existing.value = enteredValue;
                existing.points = null;
            } else {
                Grade grade = new Grade();
                grade.assessment = assessment;
                grade.student = student;
                grade.value = enteredValue;
                grade.persist();
            }
        }

        return Response.ok(averagePayload(subject, student, assessment, enteredValue)).build();
    }

    /**
     * The subject-wide ("Jahr") average is always recomputed regardless of the grid's display
     * mode - unaffected by Halbjahr. When the class has a {@code halfYearCutoff}, the edited
     * student's H1/H2 averages are recomputed too (via the same {@link #subjectAveragesForBlock}
     * that {@link #loadHalfYearGridData} uses), so both half-columns stay live without a reload -
     * see {@code updateAverageRow}'s {@code data-scope} handling in {@code notenfuchs.js}.
     */
    private CellSaveResponse averagePayload(Subject subject, Student student, Assessment assessment,
                                             BigDecimal enteredValue) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<CategoryColumns> allColumns = new ArrayList<>();
        for (GradeCategory category : categories) {
            allColumns.add(new CategoryColumns(category, Assessment.list("category.id", category.id)));
        }
        List<Student> singleStudent = List.of(student);
        SubjectAverageResult average = subjectAveragesForBlock(allColumns, singleStudent, subject).get(0);

        LocalDate cutoff = subject.schoolClass.halfYearCutoff;
        SubjectAverageResult h1Average = null;
        SubjectAverageResult h2Average = null;
        if (cutoff != null) {
            HalfSplit split = splitByHalf(allColumns, cutoff);
            h1Average = subjectAveragesForBlock(split.h1(), singleStudent, subject).get(0);
            h2Average = subjectAveragesForBlock(split.h2(), singleStudent, subject).get(0);
        }

        List<Grade> assessmentGrades = Grade.list("assessment.id", assessment.id);
        List<BigDecimal> assessmentValues = assessmentGrades.stream()
                .map(g -> effectiveGradeValue(g, assessment, subject.gradeScale))
                .filter(java.util.Objects::nonNull)
                .toList();
        SubjectAverageResult assessmentAverage = gradeService.calculateAssessmentAverage(
                assessmentValues, subject.gradeScale, subject.roundingMode);

        String displayValue = enteredValue != null ? plain(enteredValue) : "";
        String derivedGradeDisplay = null;
        if (assessment.pointsBased && enteredValue != null) {
            BigDecimal derived = pointsConversionService.convert(enteredValue,
                    bandData(assessment), subject.gradeScale, assessment.roundingMode);
            derivedGradeDisplay = plain(derived);
        }
        String rawAverageStr = average.rawAverage() != null ? plain(average.rawAverage()) : null;
        String assessmentRawAverageStr = assessmentAverage.rawAverage() != null
                ? plain(assessmentAverage.rawAverage()) : null;
        String h1RawAverageStr = h1Average != null && h1Average.rawAverage() != null
                ? plain(h1Average.rawAverage()) : null;
        String h2RawAverageStr = h2Average != null && h2Average.rawAverage() != null
                ? plain(h2Average.rawAverage()) : null;
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
    private BigDecimal effectiveGradeValue(Grade grade, Assessment assessment, GradeScale scale) {
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
        return bands.stream().map(b -> new PointsGradeBandData(b.minPoints, b.gradeValue)).toList();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(subject.name));
            CellStyle headerStyle = headerStyle(workbook);

            int assessmentColumnCount = data.categoryColumns().stream()
                    .mapToInt(CategoryColumns::columnCount).sum();
            int studentCol = 0;
            int avgRawCol = 1 + assessmentColumnCount;
            int avgFinalCol = avgRawCol + 1;

            Row headerRow1 = sheet.createRow(0);
            Row headerRow2 = sheet.createRow(1);

            setHeaderCell(headerRow1, studentCol, "Schüler", headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, studentCol, studentCol));

            int col = 1;
            for (CategoryColumns cc : data.categoryColumns()) {
                setHeaderCell(headerRow1, col, cc.category().name + " (" + plain(cc.category().weightPercent) + "%)",
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
                                assessment.name + " (Faktor " + plain(assessment.factor) + ")", headerStyle);
                        i++;
                    }
                }
                col += span;
            }

            setHeaderCell(headerRow1, avgRawCol, "Ø", headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, avgRawCol, avgRawCol));
            setHeaderCell(headerRow1, avgFinalCol, "Note", headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, avgFinalCol, avgFinalCol));

            int rowNum = 2;
            for (RowView row : data.rows()) {
                Row xlsxRow = sheet.createRow(rowNum++);
                xlsxRow.createCell(studentCol).setCellValue(row.student().effectiveName());
                List<CellView> cells = row.cells();
                for (int i = 0; i < cells.size(); i++) {
                    CellView cell = cells.get(i);
                    String exportValue = cell.pointsBased() ? cell.derivedGradeDisplay() : cell.displayValue();
                    if (exportValue != null) {
                        xlsxRow.createCell(1 + i).setCellValue(new BigDecimal(exportValue).doubleValue());
                    }
                }
                if (row.rawAverage() != null) {
                    xlsxRow.createCell(avgRawCol).setCellValue(row.rawAverage().doubleValue());
                }
                if (row.finalGrade() != null) {
                    xlsxRow.createCell(avgFinalCol).setCellValue(row.finalGrade());
                }
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
            for (int c = 0; c <= avgFinalCol; c++) {
                sheet.autoSizeColumn(c);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(subject.name));
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
            int lastCol = jahrAvgFinalCol;

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

            int rowNum = 3;
            for (HalfYearRowView row : data.rows()) {
                Row xlsxRow = sheet.createRow(rowNum++);
                xlsxRow.createCell(studentCol).setCellValue(row.student().effectiveName());
                writeCells(xlsxRow, row.undatedCells(), undatedStartCol);
                writeCells(xlsxRow, row.h1Cells(), h1StartCol);
                writeAverageWithLabel(xlsxRow, h1AvgRawCol, h1AvgFinalCol, row.h1RawAverage(), row.h1DisplayLabel());
                writeCells(xlsxRow, row.h2Cells(), h2StartCol);
                writeAverageWithLabel(xlsxRow, h2AvgRawCol, h2AvgFinalCol, row.h2RawAverage(), row.h2DisplayLabel());
                writeAverage(xlsxRow, jahrAvgRawCol, jahrAvgFinalCol, row.jahrRawAverage(), row.jahrFinalGrade());
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
            setHeaderCell(categoryRow, col, cc.category().name + " (" + plain(cc.category().weightPercent) + "%)", style);
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
                            assessment.name + " (Faktor " + plain(assessment.factor) + ")", style);
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
            CellView cell = cells.get(i);
            String exportValue = cell.pointsBased() ? cell.derivedGradeDisplay() : cell.displayValue();
            if (exportValue != null) {
                xlsxRow.createCell(startCol + i).setCellValue(new BigDecimal(exportValue).doubleValue());
            }
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
        if (displayLabel != null) {
            Cell cell = xlsxRow.createCell(finalCol);
            try {
                cell.setCellValue(new BigDecimal(displayLabel).doubleValue());
            } catch (NumberFormatException e) {
                cell.setCellValue(displayLabel);
            }
        }
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

    /** Strips German umlauts/eszett and any other non-ASCII-safe character for the plain filename attribute. */
    private static String sanitizeFilename(String raw) {
        String transliterated = raw
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        return transliterated.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String effectiveName(Student student) {
        return student.displayName != null && !student.displayName.isBlank() ? student.displayName : student.name;
    }

    private TemplateInstance withUser(TemplateInstance instance) {
        return instance
                .data("currentUserAuthenticated", currentUser.isAuthenticated())
                .data("currentUserDisplayName", currentUser.displayName().orElse(""))
                .data("localAuthActive", currentUser.localAuthActive());
    }

    // ---- View models for the Qute template (and the .xlsx export, which reuses them) ----

    /** Everything needed to render either the HTML grid or the .xlsx export - see {@link #loadGridData(Subject)}. */
    private record GridData(List<CategoryColumns> categoryColumns, List<Student> students, List<RowView> rows,
                             List<CategoryFooter> categoryFooters, int maxCol, int maxRow, boolean gridEmpty) {
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
     * @param displayValue       the raw entered value: a direct grade for a normal assessment,
     *                            or the raw points for a points-based one (never the derived grade)
     * @param derivedGradeDisplay only set for a points-based assessment with a grade entered -
     *                            the grade derived live from displayValue via the Notenschlüssel
     */
    public record CellView(int colIndex, Long assessmentId, boolean categoryStart, String displayValue,
                            boolean pointsBased, String derivedGradeDisplay) {
    }

    public record RowView(int rowIndex, StudentView student, List<CellView> cells,
                           BigDecimal rawAverage, Integer finalGrade) {
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
                                     List<CategoryFooter> undatedFooters, List<CategoryFooter> h1Footers,
                                     List<CategoryFooter> h2Footers, int maxCol, int maxRow, boolean gridEmpty) {
    }

    /** One category's Assessments split three ways by {@link HalfYearAssessmentPartitioner}. */
    private record HalfSplit(List<CategoryColumns> undated, List<CategoryColumns> h1, List<CategoryColumns> h2) {
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
                                   String h1DisplayLabel,
                                   List<CellView> h2Cells, BigDecimal h2RawAverage, Integer h2FinalGrade,
                                   String h2DisplayLabel,
                                   BigDecimal jahrRawAverage, Integer jahrFinalGrade) {
    }
}
