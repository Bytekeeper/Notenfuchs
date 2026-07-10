package de.notenfuchs.dto;

import java.math.BigDecimal;

public class StudentSubjectAverageResponse {

    public Long studentId;
    public String studentName;
    public Long subjectId;
    public String subjectName;
    public BigDecimal rawAverage;
    public Integer finalGrade;

    public StudentSubjectAverageResponse() {
    }

    public StudentSubjectAverageResponse(Long studentId, String studentName, Long subjectId, String subjectName,
                                          BigDecimal rawAverage, Integer finalGrade) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.subjectId = subjectId;
        this.subjectName = subjectName;
        this.rawAverage = rawAverage;
        this.finalGrade = finalGrade;
    }
}
