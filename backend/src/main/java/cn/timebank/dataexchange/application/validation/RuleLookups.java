package cn.timebank.dataexchange.application.validation;

import cn.timebank.dataexchange.infrastructure.StudentLookupRepository;
import cn.timebank.dataexchange.infrastructure.StudentLookupRepository.StudentLookupRow;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The named database lookups a rule may reference by name from YAML (plan
 * §3.3's {@code referenceExists} / {@code uniqueInDb} / {@code referenceConsistent}).
 *
 * <p>Indirecting through names rather than letting the YAML express queries is
 * the point: the rule file stays inert data, and every query the engine can
 * possibly run is declared here in Java, where it is reviewable. Adding a
 * lookup is a deliberate code change, not a config edit.</p>
 */
@Component
public class RuleLookups {

    private final StudentLookupRepository repository;

    public RuleLookups(StudentLookupRepository repository) {
        this.repository = repository;
    }

    /** Does a row with this key exist in the named dictionary? */
    public boolean exists(String lookup, String value) {
        return switch (lookup) {
            case "grade" -> repository.gradeExists(value);
            case "class" -> repository.classGradeId(value).isPresent();
            case "academicYear" -> repository.academicYearExists(value);
            case "studentNumber" -> repository.studentNumberExists(value);
            default -> throw new IllegalArgumentException("Unknown lookup '" + lookup + "'");
        };
    }

    /** The attribute of the referenced row that a {@code REFERENCE_CONSISTENT} rule compares against another column. */
    public Optional<String> attributeOf(String lookup, String value) {
        return switch (lookup) {
            case "classGrade" -> repository.classGradeId(value);
            default -> throw new IllegalArgumentException("Unknown lookup attribute '" + lookup + "'");
        };
    }

    /** The roster row behind a student number, for the historical import's existence and status rules. */
    public Optional<StudentLookupRow> student(String studentNumber) {
        return repository.findByStudentNumber(studentNumber);
    }
}
