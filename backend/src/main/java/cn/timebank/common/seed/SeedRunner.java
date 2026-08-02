package cn.timebank.common.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every registered {@link Seeder} in order and logs a per-seeder and
 * total row count with timing — the record B0's exit criteria asks for
 * ("runtime and resulting row counts recorded"). Only active under the
 * {@code seed} profile so it never runs against the normal application
 * schema by accident.
 *
 * Empty today (no {@link Seeder} beans exist yet); running it now proves
 * the harness works and produces a trivial "0 seeders, 0 rows" summary.
 */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final List<Seeder> seeders;

    SeedRunner(List<Seeder> seeders) {
        this.seeders = seeders.stream().sorted(Comparator.comparingInt(Seeder::order)).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Seed run starting: {} seeder(s) registered", seeders.size());
        Instant runStart = Instant.now();
        long totalRows = 0;
        for (Seeder seeder : seeders) {
            Instant start = Instant.now();
            long rows = seeder.seed();
            totalRows += rows;
            log.info("Seeder '{}' produced {} rows in {}", seeder.describe(), rows, Duration.between(start, Instant.now()));
        }
        log.info("Seed run complete: {} total rows in {}", totalRows, Duration.between(runStart, Instant.now()));
    }
}
