package cn.timebank.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need a real database. Each subclass gets its own
 * fresh, Flyway-migrated schema via {@link MySqlTestSchemaExtension} —
 * dropped afterwards, never the shared application schema (B0.13).
 *
 * {@code @DirtiesContext(AFTER_CLASS)} is load-bearing, not decoration:
 * every subclass registers the same {@code @DynamicPropertySource} method
 * (inherited from here), so Spring's test-context cache treats them as the
 * same configuration and would otherwise hand a second test class the
 * first class's cached context — meaning its DataSource, and the schema
 * baked into it at bean-creation time. Without this annotation, the
 * second class's tests fail against a schema its own {@code beforeAll}
 * never created (or one that a previous class already dropped).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@ExtendWith(MySqlTestSchemaExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlTestSchemaExtension::currentSchemaJdbcUrl);
        registry.add("spring.datasource.username", () -> System.getenv("TIMEBANK_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TIMEBANK_DB_PASSWORD"));
    }
}
