package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.SchoolLifecycleConfigDto;
import cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository;
import org.springframework.stereotype.Service;

import java.time.MonthDay;
import java.time.format.DateTimeFormatter;

/**
 * Publishes the school-year configuration the rollover already runs on, so no
 * client has to restate it.
 *
 * <p>This exists because both values were otherwise going to be copied. The exit
 * grade levels live in {@link AnnualRolloverRepository#EXIT_LEVELS}, and a UI
 * offering "grade 8 graduates / grade 12 graduates" would have carried a second
 * literal pair that nothing keeps in step with the process that actually
 * graduates people. The turnover date lives in {@link AcademicYearCalendar},
 * read from the environment, and School Info would otherwise have shown a
 * hardcoded 1 September that quietly becomes a lie the moment a deployment
 * configures something else.</p>
 *
 * <p>Read-only on purpose. Neither value is stored in the database, so there is
 * no write path to offer and no administrator screen that could change them —
 * changing either is a deployment operation (`Docs/operations/DeploymentEnv.md`).</p>
 */
@Service
public class SchoolLifecycleConfigService {

    /** `MM-DD`, the form an operator types in the env file, not MonthDay's own `--MM-dd`. */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final AcademicYearCalendar calendar;

    SchoolLifecycleConfigService(AcademicYearCalendar calendar) {
        this.calendar = calendar;
    }

    public SchoolLifecycleConfigDto get() {
        MonthDay boundary = calendar.boundary();
        return new SchoolLifecycleConfigDto(
                // Copied through a new list rather than handed out directly: the
                // constant is shared mutable state in the sense that matters here
                // — one careless caller sorting it in place would reorder what the
                // rollover graduates.
                AnnualRolloverRepository.EXIT_LEVELS.stream().sorted().toList(),
                boundary.format(MONTH_DAY));
    }
}
