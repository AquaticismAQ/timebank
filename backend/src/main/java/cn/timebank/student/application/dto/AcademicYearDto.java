package cn.timebank.student.application.dto;

import java.time.LocalDate;

public record AcademicYearDto(String id, String code, LocalDate startDate, LocalDate endDate, boolean current) {
}
