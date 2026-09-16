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
 * cannot accidentally inherit permission. {@code staging} counts as production:
 * staging systems routinely hold real or realistic student data, which is the
 * thing being protected, not the label on the box.</p>
 *
 * <p><strong>Known gap.</strong> An unrecognised or unset environment warns
 * loudly but still starts. Failing there would break local development and the
 * test suite, where {@code TIMEBANK_ENVIRONMENT} is legitimately absent and
 * resolves to {@code unspecified}. The consequence is that a production deploy
 * which simply forgets to set the variable is warned about rather than stopped.
 * Closing that properly means making dev and CI set the variable explicitly so
 * the default can become fatal — a deliberate follow-up, not an oversight.</p>
 */
@Component
class LocalStorageProductionGate implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageProductionGate.class);

    /** Environments where the local-disk adapter is an accepted trade-off. */
    private static final Set<String> NON_PRODUCTION = Set.of(
            "local", "dev", "development", "test", "test-server", "ci");

    /** Environments that must never run on local disk. */
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
        if (PRODUCTION.contains(environment)) {
            throw new IllegalStateException(
                    "timebank.environment=" + environment + " but evidence files are stored on local disk. "
                            + "Local-disk storage is a test-stage adapter and must not hold production evidence. "
                            + "Configure object storage (see Docs/implementation/14-Backend-B3F-CloudFileStorage.md), "
                            + "or set TIMEBANK_ENVIRONMENT to a non-production value if this is not production.");
        }
        if (!NON_PRODUCTION.contains(environment)) {
            log.warn("Evidence files are stored on local disk and timebank.environment is '{}', which is not a "
                            + "recognised non-production environment ({}). If this deployment is production, it is "
                            + "storing evidence on a test-stage adapter — set TIMEBANK_ENVIRONMENT explicitly.",
                    environment, NON_PRODUCTION);
        }
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
