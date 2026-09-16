package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.validation.ImportRuleSet;
import cn.timebank.dataexchange.application.validation.Rfc4180Reader;
import cn.timebank.dataexchange.application.validation.RowValidation;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns one `students` roster row into a {@link CreateStudentRequest}.
 *
 * <p><b>This class no longer contains any validation logic.</b> Every rule
 * lives in {@code import-rules.yaml} and is interpreted by {@link RuleEngine}
 * (plan §3.6). What remains here is the part that genuinely is Java: mapping
 * validated cells onto the typed request the commit path needs, which is the
 * same shape {@code POST /students} takes, since a bulk row is just that
 * request built from a CSV line instead of a form.</p>
 *
 * <p>The row is parsed with a real RFC 4180 reader rather than
 * {@code split(",", -1)}, so a name containing a comma no longer shifts every
 * column after it — see {@link Rfc4180Reader}.</p>
 */
@Component
class StudentImportRowValidator {

    static final String TYPE = "students";

    private final RuleSetSource ruleSetSource;
    private final RuleEngine ruleEngine;

    StudentImportRowValidator(RuleSetSource ruleSetSource, RuleEngine ruleEngine) {
        this.ruleSetSource = ruleSetSource;
        this.ruleEngine = ruleEngine;
    }

    sealed interface Result permits Valid, Invalid {
    }

    record Valid(CreateStudentRequest request) implements Result {
    }

    record Invalid(String field, String code, String message) implements Result {
    }

    Result validate(String line, RuleEngine.Run run, String lang) {
        ImportRuleSet ruleSet = ruleSetSource.forType(TYPE);
        List<String> cells;
        try {
            cells = Rfc4180Reader.parseLine(line);
        } catch (Rfc4180Reader.MalformedCsvException e) {
            return new Invalid("row", "PARSE_ERROR", e.getMessage());
        }

        RowValidation outcome = ruleEngine.validate(ruleSet, cells, run, lang);
        if (outcome instanceof RowValidation.Invalid invalid) {
            return new Invalid(invalid.field(), invalid.code(), invalid.message());
        }
        RowValidation.Valid valid = (RowValidation.Valid) outcome;
        return new Valid(new CreateStudentRequest(
                valid.get("studentNumber"),
                valid.get("displayName"),
                valid.get("gradeId"),
                valid.get("classId"),
                valid.get("enrollmentAcademicYearId"),
                valid.getInt("graduationYear"),
                valid.getDate("joinedAt")));
    }
}
