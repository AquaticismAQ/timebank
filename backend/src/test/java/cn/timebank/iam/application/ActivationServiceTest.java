package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.domain.ActivationCode;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.ActivationCodeRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §3.5.
 *
 * <p>The repository is a behaving fake rather than a bare stub: {@code
 * markRedeemed} really does return 1 only for the first caller, mirroring the
 * conditional {@code UPDATE ... WHERE redeemed_at IS NULL} it stands in for.
 * A stub that simply returned a canned value would make the single-use tests
 * assert their own setup.</p>
 *
 * <p><b>Not covered here:</b> that the JPQL in {@code ActivationCodeRepository}
 * compiles to that conditional UPDATE against real MySQL. That needs {@code mvn
 * verify} and a live database, which this pass could not run.</p>
 */
class ActivationServiceTest {

    private static final String USER_ID = "usr_student";
    private static final String ADMIN_ID = "usr_admin";
    private static final String IP = "203.0.113.10";

    private final ActivationCodeRepository codeRepository = mock(ActivationCodeRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final AuditService auditService = mock(AuditService.class);

    private final Map<String, ActivationCode> byHash = new HashMap<>();
    private final Map<String, ActivationCode> byId = new HashMap<>();
    private final Map<String, User> users = new HashMap<>();

    /** Set by the fake when the real code would have written redeemed_at. */
    private final Map<String, Instant> redeemedInDatabase = new HashMap<>();

    /**
     * When true the fake stops reflecting {@code redeemed_at} back onto the
     * entity it hands out, which is exactly what a lost race looks like: both
     * callers read a row that still says NULL, and only the {@code UPDATE}
     * separates them.
     */
    private boolean hideRedemptionFromReads = false;

    private User student;

    @BeforeEach
    void setUp() {
        student = User.pendingActivation(USER_ID, "31415926", "Example Student",
                "std_example", "zh-CN", Instant.now());
        users.put(USER_ID, student);
        users.put(ADMIN_ID, new User(ADMIN_ID, "admin001", "Irrelevant-Pw-12", "Example Admin",
                null, "zh-CN", Instant.now()));

        when(userRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0, String.class))));
        when(codeRepository.findByCodeHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0, String.class))));
        when(codeRepository.save(any(ActivationCode.class))).thenAnswer(inv -> {
            ActivationCode row = inv.getArgument(0);
            byHash.put(row.getCodeHash(), row);
            byId.put(row.getId(), row);
            return row;
        });
        when(codeRepository.deleteUnredeemedFor(anyString())).thenAnswer(inv -> {
            String userId = inv.getArgument(0, String.class);
            List<ActivationCode> doomed = byId.values().stream()
                    .filter(c -> c.getUserId().equals(userId) && !redeemedInDatabase.containsKey(c.getId()))
                    .toList();
            doomed.forEach(c -> {
                byId.remove(c.getId());
                byHash.remove(c.getCodeHash());
            });
            return doomed.size();
        });
        when(codeRepository.markRedeemed(anyString(), any(Instant.class))).thenAnswer(inv -> {
            String id = inv.getArgument(0, String.class);
            Instant now = inv.getArgument(1);
            if (redeemedInDatabase.containsKey(id)) {
                return 0; // WHERE redeemed_at IS NULL matched nothing
            }
            redeemedInDatabase.put(id, now);
            if (!hideRedemptionFromReads) {
                ReflectionTestUtils.setField(byId.get(id), "redeemedAt", now);
            }
            return 1;
        });
    }

    private ActivationServiceImpl service() {
        return service(new ActivationThrottle(1_000, 1_000), 7);
    }

    private ActivationServiceImpl service(ActivationThrottle throttle, int ttlDays) {
        return new ActivationServiceImpl(codeRepository, userRepository, passwordEncoder,
                auditService, throttle, ttlDays);
    }

    /** Puts a real, verifiable code in the table with an explicit expiry. */
    private String seedCode(Instant issuedAt, Instant expiresAt) {
        String code = ActivationCodeFormat.generate(new SecureRandom());
        ActivationCode row = new ActivationCode("act_" + byId.size(), USER_ID, sha256Hex(code),
                issuedAt, expiresAt, ADMIN_ID);
        byHash.put(row.getCodeHash(), row);
        byId.put(row.getId(), row);
        return code;
    }

    private String seedUsableCode() {
        return seedCode(Instant.now(), Instant.now().plus(Duration.ofDays(7)));
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void redeeming_sets_the_password_clears_the_flag_and_activates_in_one_go() {
        student.setMustChangePassword(true);
        String code = seedUsableCode();

        service().redeem(code, "Chosen-By-Student-1", IP);

        assertThat(student.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(student.isMustChangePassword()).isFalse();
        assertThat(student.hasUsableCredential()).isTrue();
        assertThat(passwordEncoder.matches("Chosen-By-Student-1", student.getPasswordHash())).isTrue();
        assertThat(redeemedInDatabase).containsKey(byId.keySet().iterator().next());
    }

    @Test
    void the_printed_grouping_and_a_lower_case_retype_both_work() {
        String code = seedUsableCode();

        service().redeem(ActivationCodeFormat.group(code).toLowerCase(), "Chosen-By-Student-1", IP);

        assertThat(student.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- single use

    @Test
    void a_code_cannot_be_redeemed_twice() {
        String code = seedUsableCode();
        ActivationServiceImpl service = service();
        service.redeem(code, "First-Choice-Aa1", IP);
        String hashAfterFirst = student.getPasswordHash();

        assertThatThrownBy(() -> service.redeem(code, "Second-Choice-Bb2", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.ALREADY_USED));

        assertThat(student.getPasswordHash()).isEqualTo(hashAfterFirst);
        assertThat(passwordEncoder.matches("Second-Choice-Bb2", student.getPasswordHash())).isFalse();
    }

    /**
     * The concurrent case, which the {@code isRedeemed()} pre-check cannot
     * cover: both callers read a row that still says NULL, so the only thing
     * separating them is the conditional UPDATE's row count. Delete the
     * {@code != 1} branch in {@code redeem} and the second password lands.
     */
    @Test
    void the_second_of_two_racing_redemptions_loses_on_the_databases_verdict() {
        hideRedemptionFromReads = true;
        String code = seedUsableCode();
        ActivationServiceImpl service = service();
        service.redeem(code, "First-Choice-Aa1", IP);
        String hashAfterFirst = student.getPasswordHash();

        assertThatThrownBy(() -> service.redeem(code, "Second-Choice-Bb2", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.ALREADY_USED));

        assertThat(student.getPasswordHash()).isEqualTo(hashAfterFirst);
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void a_code_past_its_expiry_is_refused_and_changes_nothing() {
        String code = seedCode(Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service().redeem(code, "Chosen-By-Student-1", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.EXPIRED));

        assertThat(student.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(student.hasUsableCredential()).isFalse();
        verify(codeRepository, never()).markRedeemed(anyString(), any(Instant.class));
        verify(auditService, never()).record(any(AuditEntry.class));
    }

    /**
     * The control for the test above. Without it, a {@code redeem} broken so
     * that it refuses everything would still make the expiry test pass.
     */
    @Test
    void the_same_code_one_second_before_its_expiry_still_works() {
        String code = seedCode(Instant.now().minus(Duration.ofDays(7)), Instant.now().plusSeconds(1));

        service().redeem(code, "Chosen-By-Student-1", IP);

        assertThat(student.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void the_expiry_window_comes_from_configuration() {
        Instant before = Instant.now();
        ActivationService.IssuedCode issued = service(new ActivationThrottle(1_000, 1_000), 3)
                .issue(USER_ID, ADMIN_ID);

        assertThat(issued.expiresAt())
                .isBetween(before.plus(Duration.ofDays(3)), Instant.now().plus(Duration.ofDays(3)));
    }

    // ---------------------------------------------------------------- unknown / malformed

    @Test
    void an_unknown_but_well_formed_code_is_refused_generically() {
        String never_issued = ActivationCodeFormat.generate(new SecureRandom());

        assertThatThrownBy(() -> service().redeem(never_issued, "Chosen-By-Student-1", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.INVALID));
    }

    /**
     * A failed checksum must be decided before the table is touched — that is
     * both the point of the check character and what keeps garbage input off
     * the shared failure budget.
     */
    @Test
    void a_transposed_code_is_rejected_without_a_lookup() {
        String code = seedUsableCode();
        char[] mistyped = code.toCharArray();
        int pos = firstPositionWithDistinctNeighbour(code);
        char tmp = mistyped[pos];
        mistyped[pos] = mistyped[pos + 1];
        mistyped[pos + 1] = tmp;

        assertThatThrownBy(() -> service().redeem(new String(mistyped), "Chosen-By-Student-1", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.MALFORMED));

        verify(codeRepository, never()).findByCodeHash(anyString());
        assertThat(student.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
    }

    @Test
    void an_account_an_admin_switched_off_cannot_be_reactivated_by_an_old_slip() {
        String code = seedUsableCode();
        student.setStatus(UserStatus.INACTIVE);

        assertThatThrownBy(() -> service().redeem(code, "Chosen-By-Student-1", IP))
                .isInstanceOf(ApiException.class)
                // Generic, not a distinct code: this one is about the account,
                // and saying so would turn a found slip into a fact about its owner.
                .satisfies(e -> assertThat(fieldCodeOf(e)).isEqualTo(ActivationServiceImpl.INVALID));

        assertThat(student.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(student.hasUsableCredential()).isFalse();
    }

    @Test
    void every_failure_mode_shares_one_http_status_and_names_no_account() {
        String expired = seedCode(Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        String unknown = ActivationCodeFormat.generate(new SecureRandom());
        String malformed = "0000000001";

        for (String attempt : List.of(expired, unknown, malformed)) {
            ApiException thrown = catchApiException(() -> service().redeem(attempt, "Chosen-By-Student-1", IP));
            assertThat(thrown.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
            assertThat(thrown.getMessage())
                    .doesNotContain(student.getUsername())
                    .doesNotContain(student.getDisplayName())
                    .doesNotContain(student.getStudentId())
                    .doesNotContain(attempt);
        }
    }

    // ---------------------------------------------------------------- the global failure budget

    /**
     * §2.5's global failure budget, which exists because a per-IP limit cannot
     * close the distributed case. Each guess here comes from a different
     * address, so nothing but the shared budget can stop the fourth.
     */
    @Test
    void the_global_failure_budget_stops_guessing_across_every_address() {
        ActivationThrottle throttle = new ActivationThrottle(3, 1_000);
        ActivationServiceImpl service = service(throttle, 7);

        for (int i = 1; i <= 3; i++) {
            ApiException thrown = catchApiException(() -> service.redeem(
                    ActivationCodeFormat.generate(new SecureRandom()), "Whatever-They-Type-5", "198.51.100." + rand()));
            assertThat(fieldCodeOf(thrown)).isEqualTo(ActivationServiceImpl.INVALID);
        }
        assertThat(throttle.remainingFailureBudget()).isZero();

        ApiException blocked = catchApiException(() -> service.redeem(
                ActivationCodeFormat.generate(new SecureRandom()), "Whatever-They-Type-5", "198.51.100." + rand()));
        assertThat(blocked.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);
    }

    /**
     * And the budget is a gate, not a counter: once it is empty the endpoint
     * refuses before it ever looks the code up, so the brute force actually
     * stops rather than merely being tallied.
     */
    @Test
    void an_exhausted_budget_blocks_even_a_genuine_code_before_any_lookup() {
        ActivationThrottle throttle = new ActivationThrottle(1, 1_000);
        ActivationServiceImpl service = service(throttle, 7);
        String genuine = seedUsableCode();
        catchApiException(() -> service.redeem(
                ActivationCodeFormat.generate(new SecureRandom()), "Whatever-They-Type-5", "198.51.100.7"));

        ApiException blocked = catchApiException(() -> service.redeem(genuine, "Chosen-By-Student-1", IP));

        assertThat(blocked.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);
        assertThat(student.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
    }

    /**
     * The rule that keeps the budget from being a denial-of-service lever: a
     * code failing its own checksum can never match a stored hash, so charging
     * it would let anyone empty the school's shared budget with garbage.
     */
    @Test
    void malformed_codes_do_not_drain_the_shared_budget() {
        ActivationThrottle throttle = new ActivationThrottle(2, 1_000);
        ActivationServiceImpl service = service(throttle, 7);
        String genuine = seedUsableCode();

        for (int i = 0; i < 20; i++) {
            catchApiException(() -> service.redeem("0000000001", "Whatever-They-Type-5", "198.51.100." + rand()));
        }

        assertThat(throttle.remainingFailureBudget()).isEqualTo(2);
        assertThatCode(() -> service.redeem(genuine, "Chosen-By-Student-1", IP)).doesNotThrowAnyException();
    }

    @Test
    void a_successful_activation_costs_the_budget_nothing() {
        ActivationThrottle throttle = new ActivationThrottle(5, 1_000);
        String code = seedUsableCode();

        service(throttle, 7).redeem(code, "Chosen-By-Student-1", IP);

        assertThat(throttle.remainingFailureBudget()).isEqualTo(5);
    }

    @Test
    void expired_and_already_used_codes_do_not_drain_the_shared_budget() {
        ActivationThrottle throttle = new ActivationThrottle(4, 1_000);
        ActivationServiceImpl service = service(throttle, 7);
        String expired = seedCode(Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        String usable = seedUsableCode();
        service.redeem(usable, "Chosen-By-Student-1", IP);

        catchApiException(() -> service.redeem(expired, "Chosen-By-Student-1", IP));
        catchApiException(() -> service.redeem(usable, "Chosen-By-Student-1", IP));

        assertThat(throttle.remainingFailureBudget()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- per-IP limit

    @Test
    void one_address_hammering_the_endpoint_is_cut_off_while_others_are_not() {
        ActivationThrottle throttle = new ActivationThrottle(1_000, 2);
        ActivationServiceImpl service = service(throttle, 7);

        catchApiException(() -> service.redeem(ActivationCodeFormat.generate(new SecureRandom()), "pw-attempt", IP));
        catchApiException(() -> service.redeem(ActivationCodeFormat.generate(new SecureRandom()), "pw-attempt", IP));

        ApiException blocked = catchApiException(() -> service.redeem(
                ActivationCodeFormat.generate(new SecureRandom()), "pw-attempt", IP));
        assertThat(blocked.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);

        ApiException fromElsewhere = catchApiException(() -> service.redeem(
                ActivationCodeFormat.generate(new SecureRandom()), "pw-attempt", "198.51.100.44"));
        assertThat(fromElsewhere.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- issuance

    @Test
    void issuing_stores_only_the_hash_and_returns_the_code_once() {
        ActivationService.IssuedCode issued = service().issue(USER_ID, ADMIN_ID);

        assertThat(ActivationCodeFormat.isWellFormed(issued.code())).isTrue();
        assertThat(issued.username()).isEqualTo("31415926");

        ArgumentCaptor<ActivationCode> saved = ArgumentCaptor.forClass(ActivationCode.class);
        verify(codeRepository).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).isEqualTo(sha256Hex(issued.code()));
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(issued.code());
        assertThat(saved.getValue().getIssuedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void reissuing_retires_the_slip_it_replaces() {
        ActivationServiceImpl service = service();
        String lostSlip = service.issue(USER_ID, ADMIN_ID).code();

        String replacement = service.issue(USER_ID, ADMIN_ID).code();

        assertThatThrownBy(() -> service.redeem(lostSlip, "Found-In-Corridor-3", IP))
                .isInstanceOf(ApiException.class);
        assertThat(student.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThatCode(() -> service.redeem(replacement, "Chosen-By-Student-1", IP)).doesNotThrowAnyException();
    }

    @Test
    void the_batch_seam_issues_one_distinct_code_per_student() {
        users.put("usr_second", User.pendingActivation("usr_second", "24681357", "Another Student",
                "std_another", "zh-CN", Instant.now()));

        List<ActivationService.IssuedCode> issued = service().issueAll(List.of(USER_ID, "usr_second"), ADMIN_ID);

        assertThat(issued).hasSize(2);
        assertThat(issued).extracting(ActivationService.IssuedCode::userId)
                .containsExactly(USER_ID, "usr_second");
        assertThat(issued.get(0).code()).isNotEqualTo(issued.get(1).code());
    }

    /**
     * A record's generated {@code toString()} prints every component, and both
     * components of the request are secrets — one {@code log.debug("{}",
     * request)} anywhere downstream would put a live code and a child's chosen
     * password in the server log.
     */
    @Test
    void the_request_record_redacts_itself_when_printed() {
        var request = new cn.timebank.iam.application.dto.AuthDtos
                .ActivateAccountRequest("HKTZ49QP2M", "Chosen-By-Student-1");

        assertThat(request.toString())
                .doesNotContain("HKTZ49QP2M")
                .doesNotContain("Chosen-By-Student-1")
                .contains("<redacted>");
    }

    @Test
    void the_issued_code_record_redacts_itself_when_printed() {
        ActivationService.IssuedCode issued = service().issue(USER_ID, ADMIN_ID);

        assertThat(issued.toString()).doesNotContain(issued.code()).contains("<redacted>");
    }

    // ---------------------------------------------------------------- audit

    @Test
    void activation_is_audited_and_the_audit_row_never_carries_the_code() {
        String code = seedUsableCode();

        service().redeem(code, "Chosen-By-Student-1", IP);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService, atLeastOnce()).record(entry.capture());
        AuditEntry recorded = entry.getAllValues().stream()
                .filter(e -> "user.activate".equals(e.action())).findFirst().orElseThrow();
        assertThat(recorded.actorId()).isEqualTo(USER_ID);
        assertThat(recorded.resourceType()).isEqualTo("user");
        assertThat(recorded.resourceId()).isEqualTo(USER_ID);
        assertThat(recorded.before().toString()).contains("PENDING_ACTIVATION");
        assertThat(recorded.after().toString()).contains("ACTIVE");
        assertThat(entryAsText(recorded))
                .doesNotContain(code)
                .doesNotContain(sha256Hex(code))
                .doesNotContain("Chosen-By-Student-1");
    }

    @Test
    void issuing_is_audited_and_the_audit_row_never_carries_the_code() {
        ActivationService.IssuedCode issued = service().issue(USER_ID, ADMIN_ID);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("user.issueActivationCode");
        assertThat(entry.getValue().actorId()).isEqualTo(ADMIN_ID);
        assertThat(entryAsText(entry.getValue()))
                .doesNotContain(issued.code())
                .doesNotContain(sha256Hex(issued.code()));
    }

    // ---------------------------------------------------------------- helpers

    private static int seq = 0;

    private static String rand() {
        return String.valueOf(1 + (seq++ % 200));
    }

    private static int firstPositionWithDistinctNeighbour(String code) {
        for (int i = 0; i < code.length() - 1; i++) {
            if (code.charAt(i) != code.charAt(i + 1)) {
                return i;
            }
        }
        throw new IllegalStateException("Generated a code of ten identical characters");
    }

    private static String fieldCodeOf(Throwable thrown) {
        return ((ApiException) thrown).fieldErrors().get(0).code();
    }

    private static ApiException catchApiException(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected an ApiException, but the call succeeded");
        } catch (ApiException e) {
            return e;
        }
    }

    private static String entryAsText(AuditEntry entry) {
        List<Object> parts = new ArrayList<>();
        parts.add(entry.actorId());
        parts.add(entry.permissionUsed());
        parts.add(entry.action());
        parts.add(entry.resourceType());
        parts.add(entry.resourceId());
        parts.add(entry.before());
        parts.add(entry.after());
        parts.add(entry.reasonCode());
        return parts.toString();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
