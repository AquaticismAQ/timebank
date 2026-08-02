package cn.timebank.testsupport;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Replaces what Testcontainers would otherwise give integration tests for
 * free — this project does not use Docker (Design.md §19), so integration
 * tests run against the real MySQL on the shared test server instead of a
 * throwaway per-run container (B0.13, `Docs/implementation/02-Backend-B0-Foundation.md`).
 *
 * Each test class using this extension gets its own {@code timebank_it_<random>}
 * schema, created empty in {@link #beforeAll} and dropped in {@link #afterAll}.
 * <b>The application schema ({@code timebank}) is never touched by tests</b> —
 * that is the one property this class exists to guarantee. Test classes read
 * the created schema name via {@link #currentSchemaJdbcUrl()} from a
 * {@code @DynamicPropertySource} method, which Spring evaluates after this
 * extension's {@code beforeAll} has already run.
 *
 * Requires {@code TIMEBANK_DB_HOST}/{@code _PORT}/{@code _USERNAME}/{@code _PASSWORD}
 * env vars for an account with CREATE/DROP on {@code timebank_it_%} (see
 * the test-server provisioning notes in `test-server-credits.md`, untracked).
 */
public class MySqlTestSchemaExtension implements BeforeAllCallback, AfterAllCallback {

    private static final ThreadLocal<String> SCHEMA = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String schema = "timebank_it_" + randomSuffix();
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        SCHEMA.set(schema);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        String schema = SCHEMA.get();
        if (schema == null) {
            return;
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        } finally {
            SCHEMA.remove();
        }
    }

    public static String currentSchemaJdbcUrl() {
        String schema = SCHEMA.get();
        if (schema == null) {
            throw new IllegalStateException(
                    "No test schema active — is MySqlTestSchemaExtension registered on this class?");
        }
        return "jdbc:mysql://" + host() + ":" + port() + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private static Connection adminConnection() throws java.sql.SQLException {
        String url = "jdbc:mysql://" + host() + ":" + port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return DriverManager.getConnection(url, username(), password());
    }

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(8);
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String host() {
        return env("TIMEBANK_DB_HOST", "127.0.0.1");
    }

    private static String port() {
        return env("TIMEBANK_DB_PORT", "3306");
    }

    private static String username() {
        return required("TIMEBANK_DB_USERNAME");
    }

    private static String password() {
        return required("TIMEBANK_DB_PASSWORD");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be set to run integration tests (see test-server-credits.md).");
        }
        return value;
    }
}
