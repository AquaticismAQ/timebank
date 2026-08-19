package cn.timebank.organization.application;

import cn.timebank.organization.infrastructure.OrganizationRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Docs/optimization/03-ServiceRecordFormOptimization.md §4.1/§10 Q3: fails startup
 * entirely (not a degraded/read-only mode) if the singleton school organization row
 * is missing — every PROJECT-sourced project/service-record snapshot depends on it
 * existing, and a silent auto-created replacement would have no traceable origin.
 * Throwing from {@link ApplicationRunner#run} propagates out of
 * {@code SpringApplication.run()} and aborts startup with a non-zero exit code.
 */
@Component
class SchoolOrganizationHealthCheck implements ApplicationRunner {

    private final OrganizationRepository repository;

    SchoolOrganizationHealthCheck(OrganizationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.findBySchoolOrganizationTrue().isEmpty()) {
            throw new IllegalStateException(
                    "No school organization row (organization.is_school_organization = 1) exists — "
                            + "run the V17 migration's seed, or insert one directly, before starting the app.");
        }
    }
}
