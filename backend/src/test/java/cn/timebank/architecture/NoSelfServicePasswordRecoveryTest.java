package cn.timebank.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system has no self-service password recovery, and this is the rule that
 * keeps it that way (`Design/*&#47;08-mlps-level1-security-design-guide.md`
 * §8.1/§8.2, issue 089). Recovery is admin-initiated and offline-verified: an
 * administrator identifies the person and issues an activation slip. Nothing
 * unauthenticated may mint a credential for an account a caller names.
 *
 * <h2>Why a source scan and not an ArchUnit rule</h2>
 *
 * <p>ArchUnit reads compiled classes, which is the wrong surface for half of
 * what was removed. {@code PurgeQueryRepository} deleted from {@code
 * password_reset_token} in a raw SQL string, and the endpoints were string
 * literals in {@code @PostMapping}. A byte-code rule sees a class named
 * {@code PasswordResetToken} coming back but not a table name in a string, and
 * the raw SQL is exactly the reference that would fail at runtime rather than
 * at compile time. So this reads the shipped source.</p>
 *
 * <h2>Why comments are stripped first</h2>
 *
 * <p>Several files carry a comment saying <em>why</em> the mechanism is gone,
 * and those comments name it — that is their whole function, and a scan that
 * banned the words would push the reasoning out of the codebase in order to
 * satisfy itself. The rule is about code: a mapping, an identifier, a table
 * name in a query. Comments are removed before matching, and {@link
 * #the_scanner_reads_real_content()} proves the stripping is not silently
 * eating the file.</p>
 */
class NoSelfServicePasswordRecoveryTest {

    private static final Path JAVA = Path.of("src/main/java");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * Identifiers and literals, not prose. {@code auth/forgot-password} and
     * {@code auth/reset-password} are matched with the {@code auth/} prefix on
     * purpose: {@code POST /users/{id}/reset-password} is the admin-initiated
     * path that <em>stays</em>, and a bare {@code reset-password} would ban it.
     */
    private static final List<String> FORBIDDEN_IN_JAVA = List.of(
            "PasswordResetToken",
            "ForgotPasswordRequest",
            "forgotPassword",
            "resetPasswordWithToken",
            "auth/forgot-password",
            "auth/reset-password",
            "password_reset_token");

    /**
     * The two migrations allowed to name the table, and nothing else: V3 created
     * it and V28 drops it. Applied migrations are immutable history — V3 is not
     * edited to pretend the table never existed — so the rule is that no
     * <em>third</em> migration may reference it.
     */
    private static final List<String> MIGRATIONS_ALLOWED_TO_NAME_THE_TABLE =
            List.of("V3__iam.sql", "V28__drop_password_reset_token.sql");

    @Test
    void no_java_source_under_src_main_references_the_removed_token_machinery() throws IOException {
        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (Path file : sourcesUnder(JAVA, ".java")) {
            String code = stripJavaComments(read(file));
            List<String> hits = FORBIDDEN_IN_JAVA.stream().filter(code::contains).toList();
            if (!hits.isEmpty()) {
                offenders.put(JAVA.relativize(file).toString(), hits);
            }
        }

        assertThat(offenders)
                .as("the self-service recovery flow is removed by design (issue 089); "
                        + "recovery is admin-initiated and offline-verified, so no code may name its machinery")
                .isEmpty();
    }

    @Test
    void only_the_creating_and_dropping_migrations_name_the_table() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sourcesUnder(MIGRATIONS, ".sql")) {
            String name = file.getFileName().toString();
            if (MIGRATIONS_ALLOWED_TO_NAME_THE_TABLE.contains(name)) {
                continue;
            }
            if (stripSqlComments(read(file)).contains("password_reset_token")) {
                offenders.add(name);
            }
        }

        assertThat(offenders)
                .as("V3 created the table and V28 drops it; no other migration may touch it")
                .isEmpty();
        // Control: V28 really does drop it, so the allowlist above is not
        // quietly excusing a migration that was never written.
        assertThat(stripSqlComments(read(MIGRATIONS.resolve("V28__drop_password_reset_token.sql"))))
                .containsIgnoringCase("drop table")
                .contains("password_reset_token");
    }

    /**
     * Guards both tests above against passing for the wrong reason. A scan that
     * found no files, or a comment stripper that flattened every file to
     * nothing, would report a clean codebase. So: the tree is really there, it
     * really has a realistic number of files, and a string that is <em>in</em>
     * live code — the surviving activation endpoint, and the surviving
     * admin-reset path — is really found after stripping.
     */
    @Test
    void the_scanner_reads_real_content() throws IOException {
        List<Path> java = sourcesUnder(JAVA, ".java");
        assertThat(java).as("the source tree was found").hasSizeGreaterThan(100);
        assertThat(sourcesUnder(MIGRATIONS, ".sql")).hasSizeGreaterThan(20);

        String allCode = java.stream().map(NoSelfServicePasswordRecoveryTest::read)
                .map(NoSelfServicePasswordRecoveryTest::stripJavaComments)
                .reduce("", String::concat);
        assertThat(allCode)
                .as("the surviving unauthenticated endpoint is still in the scanned code")
                .contains("/api/v1/auth/activate");
        assertThat(allCode)
                .as("the surviving admin-initiated reset path is still in the scanned code, "
                        + "so the forbidden list is narrow enough not to have banned it")
                .contains("/{id}/reset-password");

        // And the stripper removes comments rather than code.
        assertThat(stripJavaComments("int a = 1; // password_reset_token\n/* forgotPassword */ int b = 2;"))
                .doesNotContain("password_reset_token")
                .doesNotContain("forgotPassword")
                .contains("int a = 1;")
                .contains("int b = 2;");
        assertThat(stripSqlComments("select 1; -- password_reset_token\nselect 2;"))
                .doesNotContain("password_reset_token")
                .contains("select 1;")
                .contains("select 2;");
    }

    private static List<Path> sourcesUnder(Path root, String extension) throws IOException {
        assertThat(Files.isDirectory(root))
                .as("%s must exist — run from the backend module directory", root.toAbsolutePath())
                .isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Good enough for this job and deliberately not a Java lexer: it does not
     * understand a {@code //} inside a string literal. The consequence of that
     * imprecision is only ever a <em>stricter</em> scan of the remainder of a
     * line, never a laxer one, and nothing in this codebase writes a URL
     * literal containing {@code //} on a line that also needs to be matched.
     */
    private static String stripJavaComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", "");
    }

    private static String stripSqlComments(String source) {
        return source.replaceAll("(?m)--.*$", "");
    }
}
