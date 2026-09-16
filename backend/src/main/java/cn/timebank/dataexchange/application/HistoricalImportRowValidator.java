package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.validation.ImportRuleSet;
import cn.timebank.dataexchange.application.validation.Rfc4180Reader;
import cn.timebank.dataexchange.application.validation.RowValidation;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleLookups;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Turns one `serviceRecordsHistorical` row into the tuple the ledger credit
 * needs.
 *
 * <p><b>This class no longer contains any row validation logic.</b> Cell rules
 * live in {@code import-rules.yaml} and are interpreted by {@link RuleEngine}
 * (plan §3.6). Two things stay here because they are not cell validation:
 * resolving the roster row behind a validated student number, and resolving
 * which versioned {@code EffectiveRule} governs the service date — the latter
 * is a genuine domain lookup with its own failure mode
 * ({@code NO_RULE_IN_EFFECT}) that no amount of column configuration could
 * express.</p>
 */
@Component
class HistoricalImportRowValidator {

    static final String TYPE = "serviceRecordsHistorical";

    private final RuleSetSource ruleSetSource;
    private final RuleEngine ruleEngine;
    private final RuleLookups lookups;
    private final RuleResolutionService ruleResolutionService;

    HistoricalImportRowValidator(RuleSetSource ruleSetSource, RuleEngine ruleEngine, RuleLookups lookups,
                                  RuleResolutionService ruleResolutionService) {
        this.ruleSetSource = ruleSetSource;
        this.ruleEngine = ruleEngine;
        this.lookups = lookups;
        this.ruleResolutionService = ruleResolutionService;
    }

    sealed interface Result permits Valid, Invalid {
    }

    record Valid(String studentId, String gradeId, String classId, LocalDate serviceDate, int durationMinutes,
                 String description, EffectiveRule rule) implements Result {
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

        // The MUST_EXIST_IN_DB rule already proved this resolves and is ACTIVE.
        var student = lookups.student(valid.get("studentNumber")).orElseThrow();
        LocalDate serviceDate = valid.getDate("serviceDate");

        EffectiveRule rule = ruleResolutionService
                .resolveEffectiveAt(serviceDate.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC))
                .orElse(null);
        if (rule == null) {
            return new Invalid("serviceDate", "NO_RULE_IN_EFFECT", "No rule was in effect on " + serviceDate);
        }

        return new Valid(student.id(), student.gradeId(), student.classId(), serviceDate,
                valid.getInt("durationMinutes"), valid.getOrNull("description"), rule);
    }
}
