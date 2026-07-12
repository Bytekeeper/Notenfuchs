package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.Grade;
import de.notenfuchs.domain.GradeCategory;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import de.notenfuchs.service.CategoryData;
import de.notenfuchs.service.GradeData;
import de.notenfuchs.service.GradeService;
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
import java.util.ArrayList;
import java.util.List;

/**
 * The headline feature: an Excel-like grade-entry grid for a subject (students as rows,
 * assessments as columns, grouped by category), with per-cell autosave and a live
 * per-student average column driven by {@link GradeService}.
 *
 * <p>The page itself ({@link #grid(Long)}) is a normal full HTML render. Cell edits are
 * persisted via {@link #saveCell} - a small JSON endpoint called from
 * {@code static/js/notenfuchs.js} (plain fetch, not an HTMX swap, since we need
 * fine-grained keyboard-driven navigation that HTMX's declarative triggers don't fit well).
 */
@Path("/subjects/{id}/grid")
public class GradeGridResource {

    private final GradeService gradeService = new GradeService();

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    @Location("GridPage/grid.html")
    Template gridTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance grid(@PathParam("id") Long id) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
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

    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportXlsx(@PathParam("id") Long id) {
        Subject subject = guard.requireOwnedSubject(id, currentUser.effectiveSubject());
        GridData data = loadGridData(subject);

        byte[] xlsx = buildWorkbook(subject, data);

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
                    cells.add(new CellView(colIndex, null, true, null));
                    categoryDataList.add(new CategoryData(cc.category().weightPercent, List.of()));
                    continue;
                }
                boolean categoryStart = true;
                List<GradeData> gradeDataList = new ArrayList<>();
                for (Assessment assessment : cc.assessments()) {
                    Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                            assessment.id, student.id).firstResult();
                    String displayValue = grade != null ? plain(grade.value) : null;
                    cells.add(new CellView(colIndex, assessment.id, categoryStart, displayValue));
                    if (grade != null) {
                        gradeDataList.add(new GradeData(grade.value, assessment.factor));
                        columnValues.get(colIndex).add(grade.value);
                    }
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

        BigDecimal value;
        try {
            // Accept German comma decimals too, in case a client bypasses the JS normalization.
            value = new BigDecimal(trimmed.replace(",", "."));
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Ungültiger Notenwert: '" + rawValue + "'")
                    .build();
        }

        BigDecimal min = subject.gradeScale.min;
        BigDecimal max = subject.gradeScale.max;
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Note muss zwischen " + plain(min) + " und " + plain(max) + " liegen")
                    .build();
        }

        if (existing != null) {
            existing.value = value;
        } else {
            Grade grade = new Grade();
            grade.assessment = assessment;
            grade.student = student;
            grade.value = value;
            grade.persist();
        }

        return Response.ok(averagePayload(subject, student, assessment, value)).build();
    }

    private CellSaveResponse averagePayload(Subject subject, Student student, Assessment assessment,
                                             BigDecimal savedValue) {
        List<GradeCategory> categories = GradeCategory.list("subject.id", subject.id);
        List<CategoryData> categoryDataList = new ArrayList<>();
        for (GradeCategory category : categories) {
            List<Assessment> assessments = Assessment.list("category.id", category.id);
            List<GradeData> gradeDataList = new ArrayList<>();
            for (Assessment a : assessments) {
                Grade grade = Grade.find("assessment.id = ?1 and student.id = ?2",
                        a.id, student.id).firstResult();
                if (grade != null) {
                    gradeDataList.add(new GradeData(grade.value, a.factor));
                }
            }
            categoryDataList.add(new CategoryData(category.weightPercent, gradeDataList));
        }
        SubjectAverageResult average = gradeService.calculateSubjectAverage(
                categoryDataList, subject.gradeScale, subject.roundingMode);

        List<Grade> assessmentGrades = Grade.list("assessment.id", assessment.id);
        List<BigDecimal> assessmentValues = assessmentGrades.stream().map(g -> g.value).toList();
        SubjectAverageResult assessmentAverage = gradeService.calculateAssessmentAverage(
                assessmentValues, subject.gradeScale, subject.roundingMode);

        String displayValue = savedValue != null ? plain(savedValue) : "";
        String rawAverageStr = average.rawAverage() != null ? plain(average.rawAverage()) : null;
        String assessmentRawAverageStr = assessmentAverage.rawAverage() != null
                ? plain(assessmentAverage.rawAverage()) : null;
        return new CellSaveResponse(displayValue, rawAverageStr, average.finalGrade(),
                assessment.id, assessmentRawAverageStr, assessmentAverage.finalGrade());
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
                    String displayValue = cells.get(i).displayValue();
                    if (displayValue != null) {
                        xlsxRow.createCell(1 + i).setCellValue(new BigDecimal(displayValue).doubleValue());
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
                .data("currentUserDisplayName", currentUser.displayName().orElse(""));
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

    public record CellView(int colIndex, Long assessmentId, boolean categoryStart, String displayValue) {
    }

    public record RowView(int rowIndex, StudentView student, List<CellView> cells,
                           BigDecimal rawAverage, Integer finalGrade) {
    }

    /** Per-assessment ("Leistung") average across all students, shown in the grid footer. */
    public record ColumnAverageView(Long assessmentId, BigDecimal rawAverage, Integer finalGrade) {
    }

    public record CategoryFooter(GradeCategory category, List<ColumnAverageView> columnAverages) {
    }

    public record CellSaveResponse(String displayValue, String rawAverage, Integer finalGrade,
                                    Long assessmentId, String assessmentRawAverage, Integer assessmentFinalGrade) {
    }
}
