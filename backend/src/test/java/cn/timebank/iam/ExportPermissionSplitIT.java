package cn.timebank.iam;

import cn.timebank.iam.application.ActivationService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `Design/english/10-RolesAndPermissions.md` §5 and §6, proven rather than
 * read: seven export permissions replacing the blanket `exports.create`,
 * teacher holding six of them, System Admin holding neither export nor
 * import, and `exports.create` surviving as a retired row that grants
 * nothing.
 *
 * <p>The last test is the one that matters most and is the easiest to skip.
 * A fresh {@code timebank_it_*} schema never had the old grants, so every
 * assertion about them being <em>gone</em> passes vacuously here — which is
 * exactly the trap `11-DataImportExportOptimization.md` R-1 describes,
 * where editing the INSERT IGNORE seed alone leaves the live server
 * untouched and {@code mvn verify} green regardless. So
 * {@link #theMigrationRevokesOnASchemaThatAlreadyHoldsTheOldGrants()} winds
 * this schema back to its pre-V31 state, re-runs V31's own statements from
 * the classpath, and checks the revocation actually happened.</p>
 */
class ExportPermissionSplitIT extends AbstractIntegrationTest {

    /** §5.3, SDC Admin column. */
    private static final List<String> ADMIN_EXPORTS = List.of(
            "exports.studentList", "exports.serviceRecords", "exports.studentCompletion",
            "exports.studentParticipation", "exports.projects", "exports.reviewHistory",
            "exports.reviewEfficiency");

    /** §5.3, Teacher column — the same list without Review Efficiency. */
    private static final List<String> TEACHER_EXPORTS = ADMIN_EXPORTS.stream()
            .filter(code -> !code.equals("exports.reviewEfficiency"))
            .toList();

    private static final AtomicInteger ACTIVATION_IP_SEQ = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserService userService;
    @Autowired
    private ActivationService activationService;

    @Test
    void seededRolesHoldExactlyTheDesignedExportAndImportPermissions() {
        assertThat(permissionCodesOf("role_admin")).containsAll(ADMIN_EXPORTS);
        assertThat(exportCodesOf("role_admin")).containsExactlyInAnyOrderElementsOf(ADMIN_EXPORTS);
        assertThat(exportCodesOf("role_teacher")).containsExactlyInAnyOrderElementsOf(TEACHER_EXPORTS);
        // The template has to match the role it templates, or the next
        // teacher role created from it starts out unable to export (§2.2).
        assertThat(exportCodesOf("role_template_teacher")).containsExactlyInAnyOrderElementsOf(TEACHER_EXPORTS);
        assertThat(exportCodesOf("role_sysadmin")).isEmpty();
        assertThat(exportCodesOf("role_student")).isEmpty();

        // §6: imports are SDC Admin's alone again.
        assertThat(permissionCodesOf("role_admin"))
                .contains("imports.create", "imports.read", "imports.commit");
        assertThat(permissionCodesOf("role_sysadmin"))
                .doesNotContain("imports.create", "imports.read", "imports.commit");
    }

    @Test
    void exportsCreateKeepsItsRowMarkedRetiredAndGrantsNothing() {
        String description = jdbc.queryForObject(
                "SELECT description FROM permission WHERE code = 'exports.create'", String.class);
        assertThat(description).as("the row survives, never deleted and never reused (§5.2)").isNotNull();
        assertThat(description.toLowerCase()).contains("retired");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE permission_id = 'perm_exports_create'", Integer.class))
                .as("retired by revoking every grant, not by deleting the row")
                .isZero();
    }

    /**
     * The permission split is only real if the endpoint refuses. Driven over
     * HTTP with real logins, because {@code @PreAuthorize} on the controller
     * is invisible to a test that calls the service directly.
     */
    @Test
    void postExportsAuthorizesPerExportType() {
        String admin = loginAs("exp_split_admin", "admin");
        String teacher = loginAs("exp_split_teacher", "teacher");
        String sysadmin = loginAs("exp_split_sysadmin", "sysadmin");

        // A type the caller holds and a query exists for.
        assertThat(postExport(admin, "studentList").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postExport(teacher, "studentList").getStatusCode()).isEqualTo(HttpStatus.OK);

        // §5.3's one withheld type: the teacher holds the other six.
        assertThat(postExport(teacher, "reviewEfficiency").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postExport(admin, "reviewEfficiency").getStatusCode())
                .as("admin holds the permission, so it is the unbuilt query that refuses — 400, not 403")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // §6.1: System Admin holds no export permission at all.
        assertThat(postExport(sysadmin, "studentList").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postExport(sysadmin, "serviceRecords").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A retired export type has no permission, so nobody can ask for it.
        assertThat(postExport(admin, "participation").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postExport(teacher, "participation").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // /me is where the frontend learns what to render, so the codes have
        // to reach the session, not just the database.
        assertThat(get("/api/v1/me", teacher).getBody())
                .contains("exports.studentList")
                .doesNotContain("exports.reviewEfficiency")
                .doesNotContain("exports.create");
        assertThat(get("/api/v1/me", sysadmin).getBody())
                .doesNotContain("exports.")
                .doesNotContain("imports.");
    }

    /**
     * R-1, made mechanical. Every other assertion in this class would pass
     * on a schema where V31 did nothing but insert, because a fresh schema
     * has no old grants to revoke. This one puts them back — including on a
     * school-defined custom role, the case R-1 says must not be handled
     * blind — re-runs the migration, and checks each DELETE did its work.
     */
    @Test
    void theMigrationRevokesOnASchemaThatAlreadyHoldsTheOldGrants() {
        windBackToPreSplitState();
        try {
            // Sanity: the wind-back really did leave the schema in the state
            // this test claims to exercise. Without this, a wind-back that
            // silently failed would make the assertions below prove nothing.
            assertThat(rolesGranted("perm_exports_create"))
                    .containsExactlyInAnyOrder("role_admin", "role_sysadmin", "role_it_custom");
            assertThat(exportCodesOf("role_admin")).isEmpty();
            assertThat(permissionCodesOf("role_sysadmin")).contains("imports.commit");

            executeMigration("db/migration/V31__export_permissions.sql");

            assertThat(rolesGranted("perm_exports_create"))
                    .as("including the custom role — inert permissions are not left lying around (§2.1)")
                    .isEmpty();
            assertThat(exportCodesOf("role_admin")).containsExactlyInAnyOrderElementsOf(ADMIN_EXPORTS);
            assertThat(exportCodesOf("role_teacher")).containsExactlyInAnyOrderElementsOf(TEACHER_EXPORTS);
            assertThat(exportCodesOf("role_template_teacher")).containsExactlyInAnyOrderElementsOf(TEACHER_EXPORTS);
            assertThat(exportCodesOf("role_sysadmin")).isEmpty();
            assertThat(permissionCodesOf("role_sysadmin"))
                    .doesNotContain("imports.create", "imports.read", "imports.commit");
            assertThat(permissionCodesOf("role_admin"))
                    .as("admin keeps import — only sysadmin loses it (§6)")
                    .contains("imports.create", "imports.read", "imports.commit");
            assertThat(jdbc.queryForObject(
                    "SELECT description FROM permission WHERE code = 'exports.create'", String.class))
                    .containsIgnoringCase("retired");
        } finally {
            // Every test in this class shares one schema, so a failure
            // anywhere above must not leave the wound-back state behind for
            // whichever test runs next — it would fail for a reason having
            // nothing to do with what it asserts. Re-running the migration
            // is the restore, and that it can be re-run at all is worth
            // knowing: Flyway never repeats a versioned migration, but an
            // operator re-applying one by hand should not be punished.
            executeMigration("db/migration/V31__export_permissions.sql");
        }
    }

    /** Undoes V31 on this schema: the seven new rows go, the old grants come back, and a custom role holds the old permission too. */
    private void windBackToPreSplitState() {
        jdbc.update("DELETE FROM role_permission WHERE permission_id LIKE 'perm_exports_%'"
                + " AND permission_id <> 'perm_exports_create'");
        jdbc.update("DELETE FROM permission WHERE code LIKE 'exports.%' AND code <> 'exports.create'");
        jdbc.update("UPDATE permission SET description = 'Create a data export job' WHERE id = 'perm_exports_create'");
        jdbc.update("INSERT IGNORE INTO role (id, code, name, session_timeout_minutes, created_at, updated_at)"
                + " VALUES ('role_it_custom', 'it_custom', 'Custom school role', 480, NOW(3), NOW(3))");
        jdbc.update("INSERT IGNORE INTO role_permission (role_id, permission_id) VALUES"
                + " ('role_admin', 'perm_exports_create'),"
                + " ('role_sysadmin', 'perm_exports_create'),"
                + " ('role_it_custom', 'perm_exports_create'),"
                + " ('role_sysadmin', 'perm_imports_create'),"
                + " ('role_sysadmin', 'perm_imports_read'),"
                + " ('role_sysadmin', 'perm_imports_commit')");
    }

    /**
     * Runs a migration file's statements as Flyway would. Naive on purpose —
     * it strips {@code --} comments and splits on {@code ;} — which is
     * enough for a permission migration and keeps the test honest: it
     * executes the real file, not a paraphrase of it.
     */
    private void executeMigration(String classpathLocation) {
        String sql;
        try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + classpathLocation, e);
        }
        List<String> statements = new ArrayList<>();
        for (String line : sql.split("\n")) {
            if (line.stripLeading().startsWith("--")) continue;
            statements.add(line);
        }
        for (String statement : String.join("\n", statements).split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    /** The per-type export permissions a role holds. Deliberately excludes the retired {@code exports.create}, which is asserted on separately — folding it in here would let a role that still held it read as correctly permissioned. */
    private List<String> exportCodesOf(String roleId) {
        return permissionCodesOf(roleId).stream()
                .filter(code -> code.startsWith("exports.") && !code.equals("exports.create"))
                .toList();
    }

    private List<String> permissionCodesOf(String roleId) {
        return jdbc.queryForList(
                "SELECT p.code FROM role_permission rp JOIN permission p ON p.id = rp.permission_id"
                        + " WHERE rp.role_id = ?", String.class, roleId);
    }

    private List<String> rolesGranted(String permissionId) {
        return jdbc.queryForList("SELECT role_id FROM role_permission WHERE permission_id = ?",
                String.class, permissionId);
    }

    private ResponseEntity<String> postExport(String sessionCookie, String reportType) {
        String csrfCookie = fetchCsrfCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
        headers.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        return restTemplate.postForEntity("/api/v1/exports",
                new HttpEntity<>("{\"reportType\":\"" + reportType + "\"}", headers), String.class);
    }

    private ResponseEntity<String> get(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * Creates an account in the given role, puts it through the real
     * activation flow (the only way any account acquires a password since
     * issue 096) and logs in.
     *
     * <p>The account issues its own activation code. {@code
     * activation_code.issued_by} is a real FK to {@code user}, and on a
     * fresh schema there is no {@code user} row to name as the issuer — a
     * literal {@code "system"} violates it. Who issued a fixture's code is
     * irrelevant to what this class asserts; that it is a real user id is
     * not.</p>
     */
    private String loginAs(String username, String roleCode) {
        UserDto created = userService.create(new CreateUserRequest(username, "Display " + username,
                username + "@example.com", roleCode, "zh-CN"), "system");
        String password = "InitialChoice123!";
        activationService.redeem(activationService.issue(created.id(), created.id()).code(), password,
                "198.51.100." + ACTIVATION_IP_SEQ.incrementAndGet());

        String csrfCookie = fetchCsrfCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, csrfCookie);
        headers.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/login",
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String session = extractCookie(response, "TIMEBANK_SESSION");
        assertThat(session).as("session cookie for %s", username).isNotNull();
        return session;
    }

    private String fetchCsrfCookie() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/csrf", String.class);
        String cookie = extractCookie(response, "XSRF-TOKEN");
        assertThat(cookie).as("CSRF cookie must be issued").isNotNull();
        return cookie;
    }

    private String extractCookie(ResponseEntity<?> response, String cookieName) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        return setCookies.stream()
                .filter(c -> c.startsWith(cookieName + "="))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }

    private String cookieValue(String cookiePair) {
        return cookiePair.substring(cookiePair.indexOf('=') + 1);
    }
}
