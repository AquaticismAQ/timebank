package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.infrastructure.StudentLookupRepository;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * B5.10b: one row of the `students` roster import is {@code
 * studentNumber,displayName,gradeId,classId,enrollmentAcademicYearId,
 * graduationYear,joinedAt,email,guardianName,guardianContact} — the same
 * fields {@code POST /students} (the single-student create path,
 * {@link cn.timebank.student.application.StudentService#create}) requires,
 * since a bulk row is just that request built from a CSV line instead of a
 * form. Shared by both the validate run and the commit run for the same
 * reason as {@link HistoricalImportRowValidator}: commit re-validates
 * rather than trusting a stored intermediate result.
 *
 * <p>{@code seenInFile} catches a student number repeated within the same
 * upload before either phase has persisted anything — the commit phase's
 * own row-by-row {@code studentNumberExists} check would eventually catch
 * a same-file duplicate too (the first occurrence gets persisted before
 * the second is checked), but only on the second occurrence, and only
 * during commit; without this, the read-only validate pass would wrongly
 * report both rows valid.</p>
 */
@Component
class StudentImportRowValidator {

    private final StudentLookupRepository studentLookupRepository;

    StudentImportRowValidator(StudentLookupRepository studentLookupRepository) {
        this.studentLookupRepository = studentLookupRepository;
    }

    sealed interface Result permits Valid, Invalid {
    }

    record Valid(CreateStudentRequest request) implements Result {
    }

    record Invalid(String field, String code, String message) implements Result {
    }

    Result validate(String line, Set<String> seenInFile) {
        String[] parts = line.split(",", -1);
        if (parts.length < 7) {
            return new Invalid("row", "PARSE_ERROR",
                    "Expected studentNumber/displayName/gradeId/classId/enrollmentAcademicYearId/graduationYear/joinedAt[/email/guardianName/guardianContact]");
        }
        String studentNumber = parts[0].trim();
        String displayName = parts[1].trim();
        String gradeId = parts[2].trim();
        String classId = parts[3].trim();
        String academicYearId = parts[4].trim();
        String graduationYearRaw = parts[5].trim();
        String joinedAtRaw = parts[6].trim();
        String email = blankToNull(parts.length > 7 ? parts[7].trim() : "");
        String guardianName = blankToNull(parts.length > 8 ? parts[8].trim() : "");
        String guardianContact = blankToNull(parts.length > 9 ? parts[9].trim() : "");

        if (studentNumber.isEmpty()) {
            return new Invalid("studentNumber", "REQUIRED", "studentNumber is required");
        }
        if (displayName.isEmpty()) {
            return new Invalid("displayName", "REQUIRED", "displayName is required");
        }
        if (seenInFile.contains(studentNumber)) {
            return new Invalid("studentNumber", "DUPLICATE_IN_FILE", "studentNumber " + studentNumber + " appears more than once in this file");
        }
        if (studentLookupRepository.studentNumberExists(studentNumber)) {
            return new Invalid("studentNumber", "STUDENT_NUMBER_TAKEN", "Student number " + studentNumber + " is already in use");
        }

        if (gradeId.isEmpty() || !studentLookupRepository.gradeExists(gradeId)) {
            return new Invalid("gradeId", "UNKNOWN_GRADE", "Unknown gradeId: " + gradeId);
        }
        var classGradeId = studentLookupRepository.classGradeId(classId);
        if (classGradeId.isEmpty()) {
            return new Invalid("classId", "UNKNOWN_CLASS", "Unknown classId: " + classId);
        }
        if (!classGradeId.get().equals(gradeId)) {
            return new Invalid("classId", "CLASS_GRADE_MISMATCH", "Class " + classId + " does not belong to grade " + gradeId);
        }
        if (academicYearId.isEmpty() || !studentLookupRepository.academicYearExists(academicYearId)) {
            return new Invalid("enrollmentAcademicYearId", "UNKNOWN_ACADEMIC_YEAR", "Unknown enrollmentAcademicYearId: " + academicYearId);
        }

        int graduationYear;
        try {
            graduationYear = Integer.parseInt(graduationYearRaw);
        } catch (NumberFormatException e) {
            return new Invalid("graduationYear", "INVALID_YEAR", "Not a number: " + graduationYearRaw);
        }
        if (graduationYear < 2000) {
            return new Invalid("graduationYear", "INVALID_YEAR", "graduationYear must be 2000 or later");
        }

        LocalDate joinedAt;
        try {
            joinedAt = LocalDate.parse(joinedAtRaw);
        } catch (Exception e) {
            return new Invalid("joinedAt", "INVALID_DATE", "Expected yyyy-MM-dd format, got '" + joinedAtRaw + "'");
        }

        seenInFile.add(studentNumber);
        return new Valid(new CreateStudentRequest(studentNumber, displayName, email, gradeId, classId,
                academicYearId, graduationYear, joinedAt, guardianName, guardianContact));
    }

    private String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
