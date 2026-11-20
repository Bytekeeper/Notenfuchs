package de.notenfuchs.dto;

import java.math.BigDecimal;

public class StudentSubjectAverageResponse {

    public Long studentId;
    public String studentName;
    public Long subjectId;
    public String subjectName;
    public BigDecimal rawAverage;
    public Integer finalGrade;
    /** The Zeugnisnote for the year if a teacher set one by hand, else null; it counts instead of {@code finalGrade}. */
    public Integer reportGrade;

    public StudentSubjectAverageResponse() {
    }

    public Integer effectiveFinalGrade() {
        return reportGrade != null ? reportGrade : finalGrade;
    }

    public StudentSubjectAverageResponse(Long studentId, String studentName, Long subjectId, String subjectName,
                                          BigDecimal rawAverage, Integer finalGrade, Integer reportGrade) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.subjectId = subjectId;
        this.subjectName = subjectName;
        this.rawAverage = rawAverage;
        this.finalGrade = finalGrade;
        this.reportGrade = reportGrade;
    }
}
