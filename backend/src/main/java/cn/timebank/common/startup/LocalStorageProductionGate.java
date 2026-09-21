package cn.timebank.common.startup;

import cn.timebank.file.application.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Docs/optimization/08-SecurityPrivacyHardening.md §3.4 (finding W-7): the
 * local-disk {@code FileStorage} adapter is a test-stage substitute for real
 * object storage. It has no redundancy, no server-side encryption at rest, and
 * lives on the same disk as everything else — evidence photographs of minors
 * are exactly the data that must not sit there in production.
 *
 * <p>Nothing prevented a production deployment from running on it. This refuses
 * to start rather than degrading quietly, matching
 * {@code SchoolOrganizationHealthCheck}, {@code TIMEBANK_ALLOWED_ORIGINS}'
 * missing default, and {@code TIMEBANK_FILE_TOKEN_SECRET:?} — throwing from
 * {@link ApplicationRunner#run} propagates out of {@code SpringApplication.run()}
 * and aborts with a non-zero exit code.</p>
 *
 * <p><strong>On "production".</strong> The check is an allow-list of environments
 * known to be safe for local disk, not a deny-list, so a new environment name
 * cannot accidentally inherit permission. {@code staging} is not on it: staging
 * systems routinely hold real or realistic student data, which is the thing
 * being protected, not the label on the box.</p>
 *
 * <p><strong>An unset variable is a refusal, not a warning</strong> (issue 148).
 * It used to be a warning, because failing would have broken local development
 * and the test suite — both of which then left {@code TIMEBANK_ENVIRONMENT}
 * unset. That has been fixed at the source (the {@code it} profile and the
 * compose file now set it), so the default can be fatal, which is what closes
 * the case this gate actually exists for: a production deployment that forgets
 * the variable. That is not an exotic slip, it is the most likely way to end up
 * in production on a test-stage adapter, and it was the one case the gate used
 * to wave through. A line in a startup log is not a control.</p>
 */
@Component
class LocalStorageProductionGate implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageProductionGate.class);

    /**
     * Environments where the local-disk adapter is an accepted trade-off. This
     * is the whole permission: anything absent from it is refused.
     */
    private static final Set<String> NON_PRODUCTION = Set.of(
            "local", "dev", "development", "test", "test-server", "ci", "it");

    /**
     * Names that are certainly production. Not a second gate — everything
     * outside {@link #NON_PRODUCTION} is refused either way — only which of two
     * messages the operator gets, since "you said production" and "you said
     * nothing" call for different instructions.
     */
    private static final Set<String> PRODUCTION = Set.of("production", "prod", "staging");

    private final FileStorage fileStorage;
    private final String environment;

    LocalStorageProductionGate(FileStorage fileStorage,
                               @Value("${timebank.environment:unspecified}") String environment) {
        this.fileStorage = fileStorage;
        this.environment = environment == null ? "unspecified" : environment.trim().toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!usingLocalDiskStorage()) {
            return;
        }
        if (NON_PRODUCTION.contains(environment)) {
            return;
        }
        if (PRODUCTION.contains(environment)) {
            throw new IllegalStateException(
                    "timebank.environment=" + environment + " but evidence files are stored on local disk. "
                            + "Local-disk storage is a test-stage adapter and must not hold production evidence. "
                            + "Configure object storage (see Docs/implementation/14-Backend-B3F-CloudFileStorage.md), "
                            + "or set TIMEBANK_ENVIRONMENT to a non-production value if this is not production.");
        }
        throw new IllegalStateException(
                "timebank.environment is '" + environment + "', which is not a recognised non-production "
                        + "environment, and evidence files are stored on local disk. Refusing to start rather "
                        + "than guessing: an unset variable is how a production deployment ends up on a "
                        + "test-stage adapter. Set TIMEBANK_ENVIRONMENT to one of " + sortedNonProduction()
                        + ", or configure object storage "
                        + "(see Docs/implementation/14-Backend-B3F-CloudFileStorage.md).");
    }

    /** Sorted so the message reads the same every time — it is the instruction, not a dump. */
    private static String sortedNonProduction() {
        return NON_PRODUCTION.stream().sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * {@code LocalFileStorage} is package-private inside {@code cn.timebank.file},
     * deliberately — the adapter is not part of that module's public surface, and
     * widening it just for an {@code instanceof} here would leak an implementation
     * detail across a module boundary. Matching on the bean's class name keeps the
     * seam intact; it is checked against the concrete class rather than the
     * interface, so a future object-storage adapter simply does not match.
     */
    private boolean usingLocalDiskStorage() {
        return fileStorage.getClass().getName().contains("LocalFileStorage");
    }
}
