package de.notenfuchs.web;

import de.notenfuchs.domain.SchoolClass;
import de.notenfuchs.domain.Student;
import de.notenfuchs.domain.Subject;
import de.notenfuchs.dto.StudentSubjectAverageResponse;
import de.notenfuchs.rest.ClassAveragesResource;
import de.notenfuchs.security.CurrentUser;
import de.notenfuchs.security.OwnershipGuard;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One xlsx for a whole class, following the access model rather than widening it: a class admin
 * gets a "Notenübersicht" sheet with every Fach's final grade (what the Notenübersicht tab shows),
 * and everyone gets one full sheet per Fach they teach (the same as that Fach's own export).
 */
@Path("/classes/{id}/export")
public class ClassExportResource {

    static final String OVERVIEW_SHEET = "Notenübersicht";

    @Inject
    CurrentUser currentUser;

    @Inject
    OwnershipGuard guard;

    @Inject
    GradeGridResource grid;

    @Inject
    ClassAveragesResource classAveragesResource;

    @GET
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response export(@PathParam("id") Long id) {
        String currentSubject = currentUser.effectiveSubject();
        SchoolClass schoolClass = guard.requireClassAccess(id, currentSubject);
        boolean admin = guard.isAdmin(schoolClass, currentSubject);
        List<Subject> subjects = Subject.list("schoolClass.id = ?1 order by name", id);
        List<Subject> taught = subjects.stream().filter(s -> guard.teachesSubject(s, currentSubject)).toList();
        if (!admin && taught.isEmpty()) {
            // Nothing this viewer may export; the class page doesn't offer the button either.
            throw new NotFoundException("Nothing to export for class " + id);
        }

        byte[] xlsx = GradeGridResource.toBytes(workbook -> {
            Set<String> usedNames = new HashSet<>();
            if (admin) {
                usedNames.add(OVERVIEW_SHEET.toLowerCase(Locale.ROOT));
                writeOverviewSheet(workbook, id, subjects);
            }
            for (Subject subject : taught) {
                grid.addSubjectSheet(workbook, uniqueSheetName(subject.name, usedNames), subject);
            }
        });

        String fileName = schoolClass.name + "-Noten.xlsx";
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"" + DownloadFilenames.sanitize(schoolClass.name)
                        + "-Noten.xlsx\"; filename*=UTF-8''"
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"))
                .build();
    }

    private void writeOverviewSheet(XSSFWorkbook workbook, Long classId, List<Subject> subjects) {
        Map<Long, Map<Long, Integer>> finalGrades = new HashMap<>();
        for (StudentSubjectAverageResponse average : classAveragesResource.averages(classId)) {
            finalGrades.computeIfAbsent(average.studentId, k -> new HashMap<>()).put(average.subjectId, average.effectiveFinalGrade());
        }
        Sheet sheet = workbook.createSheet(OVERVIEW_SHEET);
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Schüler");
        for (int i = 0; i < subjects.size(); i++) {
            header.createCell(i + 1).setCellValue(subjects.get(i).name);
        }
        List<Student> students = new ArrayList<>(Student.<Student>list(
                "schoolClass.id = ?1 and leftOn is null order by name", classId));
        students.addAll(Student.list("schoolClass.id = ?1 and leftOn is not null order by name", classId));
        int rowNum = 1;
        for (Student student : students) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(student.hasLeft()
                    ? student.effectiveName() + " (ausgetreten)" : student.effectiveName());
            Map<Long, Integer> grades = finalGrades.getOrDefault(student.id, Map.of());
            for (int i = 0; i < subjects.size(); i++) {
                Integer grade = grades.get(subjects.get(i).id);
                if (grade != null) {
                    row.createCell(i + 1).setCellValue(grade);
                }
            }
        }
        sheet.createFreezePane(1, 1);
        for (int c = 0; c <= subjects.size(); c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /** Excel sheet names: at most 31 characters, no special characters, unique ignoring case. */
    static String uniqueSheetName(String name, Set<String> usedLowerCase) {
        String base = WorkbookUtil.createSafeSheetName(name);
        String candidate = base;
        for (int n = 2; usedLowerCase.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " (" + n + ")";
            candidate = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
        }
        usedLowerCase.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }
}
