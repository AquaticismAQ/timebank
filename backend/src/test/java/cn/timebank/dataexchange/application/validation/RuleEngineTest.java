package cn.timebank.dataexchange.application.validation;

import cn.timebank.dataexchange.infrastructure.StudentLookupRepository.StudentLookupRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §3.3/§3.4. Exercises the real {@code import-rules.yaml} — not a
 * hand-built fixture — so a rule accidentally deleted from the file fails
 * here rather than silently letting hostile input through.
 *
 * <p>Database lookups are stubbed, because what is under test is the engine's
 * interpretation of the rules, not SQL. The live-schema behaviour is covered
 * by {@code ImportExportIT}.</p>
 */
class RuleEngineTest {

    /** Everything the roster fixture below considers to exist. */
    private static final Set<String> KNOWN_GRADES = Set.of("grade_6", "grade_9");
    private static final Set<String> KNOWN_CLASSES = Set.of("class_6A", "class_9A");
    private static final Set<String> TAKEN_NUMBERS = Set.of("taken001");

    private RuleEngine engine;
    private ImportRuleSet students;
    private RuleEngine.Run run;

    @BeforeEach
    void setUp() {
        RuleLookups lookups = new RuleLookups(null) {
            @Override
            public boolean exists(String lookup, String value) {
                return switch (lookup) {
                    case "grade" -> KNOWN_GRADES.contains(value);
                    case "class" -> KNOWN_CLASSES.contains(value);
                    case "academicYear" -> "ay_2025_2026".equals(value);
                    case "studentNumber" -> TAKEN_NUMBERS.contains(value);
                    default -> false;
                };
            }

            @Override
            public Optional<String> attributeOf(String lookup, String value) {
                // class_6A belongs to grade_6, class_9A to grade_9.
                return switch (value) {
                    case "class_6A" -> Optional.of("grade_6");
                    case "class_9A" -> Optional.of("grade_9");
                    default -> Optional.empty();
                };
            }

            @Override
            public Optional<StudentLookupRow> student(String studentNumber) {
                return switch (studentNumber) {
                    case "active01" -> Optional.of(new StudentLookupRow("std_1", "grade_6", "class_6A", "ACTIVE"));
                    case "left01" -> Optional.of(new StudentLookupRow("std_2", "grade_6", "class_6A", "INACTIVE"));
                    default -> Optional.empty();
                };
            }
        };
        engine = new RuleEngine(lookups);
        students = new YamlRuleSetSource().forType("students");
        run = engine.newRun();
    }

    /** A row that should pass every rule, used as the baseline the failures deviate from. */
    private List<String> validRow() {
        return List.of("a45b9def", "Alex L.", "grade_6", "class_6A", "ay_2025_2026", "2032", "2026-09-01");
    }

    private RowValidation validate(List<String> row) {
        return engine.validate(students, row, run, "en");
    }

    private RowValidation validateWith(int column, String value) {
        List<String> row = new java.util.ArrayList<>(validRow());
        row.set(column, value);
        return validate(row);
    }

    private void assertRejected(RowValidation outcome, String field, String code) {
        assertThat(outcome).isInstanceOf(RowValidation.Invalid.class);
        RowValidation.Invalid invalid = (RowValidation.Invalid) outcome;
        assertThat(invalid.field()).isEqualTo(field);
        assertThat(invalid.code()).isEqualTo(code);
        assertThat(invalid.message()).as("a rejected row must carry a human message, not just a code").isNotBlank();
    }

    @Test
    void acceptsAWellFormedRow() {
        assertThat(validate(validRow())).isInstanceOf(RowValidation.Valid.class);
    }

    @Test
    void rejectsAMissingStudentNumber() {
        assertRejected(validateWith(0, ""), "studentNumber", "REQUIRED");
    }

    @Test
    void rejectsAFormulaInjectionName() {
        // CWE-1236: executes in a spreadsheet when the export is opened.
        assertRejected(validateWith(1, "=HYPERLINK(\"http://evil\",\"click\")"), "displayName", "UNSAFE_CONTENT");
    }

    @Test
    void rejectsEveryFormulaPrefix() {
        for (String prefix : List.of("=", "+", "-", "@")) {
            RuleEngine.Run fresh = engine.newRun();
            List<String> row = new java.util.ArrayList<>(validRow());
            row.set(1, prefix + "danger");
            RowValidation outcome = engine.validate(students, row, fresh, "en");
            assertThat(outcome).as("prefix %s must be rejected", prefix).isInstanceOf(RowValidation.Invalid.class);
            assertThat(((RowValidation.Invalid) outcome).code()).isEqualTo("UNSAFE_CONTENT");
        }
    }

    @Test
    void rejectsABidirectionalOverrideCharacter() {
        // U+202E makes the rendered name differ from the stored one.
        assertRejected(validateWith(1, "Alex\u202EL."), "displayName", "UNSAFE_CONTENT");
    }

    @Test
    void rejectsANulByte() {
        assertRejected(validateWith(1, "Alex\u0000L."), "displayName", "UNSAFE_CONTENT");
    }

    @Test
    void rejectsAnOverLongName() {
        assertRejected(validateWith(1, "x".repeat(101)), "displayName", "INVALID_FORMAT");
    }

    @Test
    void acceptsANameExactlyAtTheLimit() {
        assertThat(validateWith(1, "x".repeat(100))).isInstanceOf(RowValidation.Valid.class);
    }

    @Test
    void rejectsAStudentNumberWithDisallowedPunctuation() {
        assertRejected(validateWith(0, "a45b/9def"), "studentNumber", "INVALID_FORMAT");
    }

    @Test
    void acceptsCjkAndParenthesesInADisplayName() {
        // The masked-name convention: Alex L.(李*红). Must not be caught by the
        // control-character or formula rules.
        assertThat(validateWith(1, "Alex L.(李*红)")).isInstanceOf(RowValidation.Valid.class);
    }

    @Test
    void rejectsAnUnknownGrade() {
        assertRejected(validateWith(2, "grade_99"), "gradeId", "UNKNOWN_GRADE");
    }

    @Test
    void rejectsAnUnknownClass() {
        assertRejected(validateWith(3, "class_99Z"), "classId", "UNKNOWN_CLASS");
    }

    @Test
    void rejectsAClassThatBelongsToAnotherGrade() {
        assertRejected(validateWith(3, "class_9A"), "classId", "CLASS_GRADE_MISMATCH");
    }

    @Test
    void rejectsAnUnknownAcademicYear() {
        assertRejected(validateWith(4, "ay_1999_2000"), "enrollmentAcademicYearId", "UNKNOWN_ACADEMIC_YEAR");
    }

    @Test
    void rejectsANonNumericGraduationYear() {
        assertRejected(validateWith(5, "soon"), "graduationYear", "INVALID_YEAR");
    }

    @Test
    void rejectsAGraduationYearBefore2000() {
        assertRejected(validateWith(5, "1999"), "graduationYear", "INVALID_YEAR");
    }

    @Test
    void rejectsABadlyFormattedDate() {
        assertRejected(validateWith(6, "01/09/2026"), "joinedAt", "INVALID_DATE");
    }

    @Test
    void rejectsAStudentNumberRepeatedInTheSameFile() {
        assertThat(validate(validRow())).isInstanceOf(RowValidation.Valid.class);
        assertRejected(validate(validRow()), "studentNumber", "DUPLICATE_IN_FILE");
    }

    @Test
    void rejectsAStudentNumberAlreadyInTheDatabase() {
        assertRejected(validateWith(0, "taken001"), "studentNumber", "STUDENT_NUMBER_TAKEN");
    }

    @Test
    void rejectsARowWithTooFewColumns() {
        assertRejected(validate(List.of("a45b9def", "Alex L.")), "row", "PARSE_ERROR");
    }

    @Test
    void reportsTheChineseMessageWhenAskedFor() {
        RowValidation outcome = engine.validate(students,
                List.of("", "Alex L.", "grade_6", "class_6A", "ay_2025_2026", "2032", "2026-09-01"),
                engine.newRun(), "zh-CN");
        assertThat(((RowValidation.Invalid) outcome).message()).isEqualTo("学号为必填项");
    }

    @Test
    void fallsBackToEnglishForAnUnknownLanguage() {
        // A missing translation must never turn a rejected row into a 500.
        RowValidation outcome = engine.validate(students,
                List.of("", "Alex L.", "grade_6", "class_6A", "ay_2025_2026", "2032", "2026-09-01"),
                engine.newRun(), "fr");
        assertThat(((RowValidation.Invalid) outcome).message()).isEqualTo("studentNumber is required");
    }

    @Test
    void historicalRowRejectsAnUnknownStudent() {
        ImportRuleSet historical = new YamlRuleSetSource().forType("serviceRecordsHistorical");
        RowValidation outcome = engine.validate(historical,
                List.of("nosuch", "2024-10-01", "120", "Cleanup"), engine.newRun(), "en");
        assertRejected(outcome, "studentNumber", "STUDENT_NOT_FOUND");
    }

    @Test
    void historicalRowRejectsAnInactiveStudent() {
        ImportRuleSet historical = new YamlRuleSetSource().forType("serviceRecordsHistorical");
        RowValidation outcome = engine.validate(historical,
                List.of("left01", "2024-10-01", "120", "Cleanup"), engine.newRun(), "en");
        assertRejected(outcome, "studentNumber", "STUDENT_INACTIVE");
    }

    @Test
    void historicalRowRejectsANonPositiveDuration() {
        ImportRuleSet historical = new YamlRuleSetSource().forType("serviceRecordsHistorical");
        RowValidation outcome = engine.validate(historical,
                List.of("active01", "2024-10-01", "0", "Cleanup"), engine.newRun(), "en");
        assertRejected(outcome, "durationMinutes", "INVALID_DURATION");
    }

    @Test
    void historicalRowAcceptsAnOmittedDescription() {
        ImportRuleSet historical = new YamlRuleSetSource().forType("serviceRecordsHistorical");
        RowValidation outcome = engine.validate(historical,
                List.of("active01", "2024-10-01", "120", ""), engine.newRun(), "en");
        assertThat(outcome).isInstanceOf(RowValidation.Valid.class);
    }
}
