package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.infrastructure.StudentLookupRepository;
import cn.timebank.dataexchange.infrastructure.StudentLookupRepository.StudentLookupRow;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * B5.10: one row of `serviceRecordsHistorical` is {@code
 * studentNumber,serviceDate,durationMinutes,description}. Shared by both
 * the validate run and the commit run (commit re-validates rather than
 * trusting a stored intermediate result) so the two phases can never
 * disagree about which rows are valid.
 */
@Component
class HistoricalImportRowValidator {

    private final StudentLookupRepository studentLookupRepository;
    private final RuleResolutionService ruleResolutionService;

    HistoricalImportRowValidator(StudentLookupRepository studentLookupRepository, RuleResolutionService ruleResolutionService) {
        this.studentLookupRepository = studentLookupRepository;
        this.ruleResolutionService = ruleResolutionService;
    }

    sealed interface Result permits Valid, Invalid {
    }

    record Valid(String studentId, String gradeId, String classId, LocalDate serviceDate, int durationMinutes,
                 String description, EffectiveRule rule) implements Result {
    }

    record Invalid(String field, String code, String message) implements Result {
    }

    Result validate(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length < 3) {
            return new Invalid("row", "PARSE_ERROR", "Expected studentNumber/serviceDate/durationMinutes[/description]");
        }
        String studentNumber = parts[0].trim();
        String serviceDateRaw = parts[1].trim();
        String durationRaw = parts[2].trim();
        String description = parts.length > 3 ? parts[3].trim() : null;

        if (studentNumber.isEmpty()) {
            return new Invalid("studentNumber", "REQUIRED", "studentNumber is required");
        }
        Optional<StudentLookupRow> student = studentLookupRepository.findByStudentNumber(studentNumber);
        if (student.isEmpty()) {
            return new Invalid("studentNumber", "STUDENT_NOT_FOUND", "No student with number " + studentNumber);
        }
        if (!"ACTIVE".equals(student.get().status())) {
            return new Invalid("studentNumber", "STUDENT_INACTIVE", "Student " + studentNumber + " is not active");
        }

        LocalDate serviceDate;
        try {
            serviceDate = LocalDate.parse(serviceDateRaw);
        } catch (Exception e) {
            return new Invalid("serviceDate", "INVALID_DATE", "Expected yyyy-MM-dd format, got '" + serviceDateRaw + "'");
        }

        int durationMinutes;
        try {
            durationMinutes = Integer.parseInt(durationRaw);
        } catch (NumberFormatException e) {
            return new Invalid("durationMinutes", "INVALID_DURATION", "Not a number: " + durationRaw);
        }
        if (durationMinutes <= 0) {
            return new Invalid("durationMinutes", "INVALID_DURATION", "Duration must be positive");
        }

        EffectiveRule rule = ruleResolutionService
                .resolveEffectiveAt(serviceDate.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC))
                .orElse(null);
        if (rule == null) {
            return new Invalid("serviceDate", "NO_RULE_IN_EFFECT", "No rule was in effect on " + serviceDate);
        }

        return new Valid(student.get().id(), student.get().gradeId(), student.get().classId(),
                serviceDate, durationMinutes, description, rule);
    }
}
