package cn.timebank.iam.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.CredentialSlipService.ResetSlip;
import cn.timebank.iam.application.CredentialSlipService.SlipActor;
import cn.timebank.iam.domain.ActivationCode;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.ActivationCodeRepository;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import cn.timebank.security.application.CookieSessionUserDetails;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §3.7, and the claim it makes true:
 * §2.4's "no password is ever generated server-side, so no password plaintext
 * exists at any point" — which was false while {@code adminResetPassword}
 * generated one and returned it in the response body (issue 088).
 *
 * <h2>Why this test wires the real services together</h2>
 *
 * <p>Nothing here is stubbed on the path that matters: the real
 * {@link UserServiceImpl} clears the credential, the real
 * {@link ActivationServiceImpl} mints and later redeems the code, the real
 * {@link CredentialSlipServiceImpl} orders the two and renders the document,
 * and the real {@link DaoAuthenticationProvider} decides whether a password
 * still authenticates. Only the repositories are fakes, and they behave —
 * {@code markRedeemed} returns 1 exactly once, as the conditional {@code
 * UPDATE ... WHERE redeemed_at IS NULL} it stands in for does.</p>
 *
 * <p>The activation code used in {@link #redeeming_the_slips_code_sets_a_new_password_and_reactivates()}
 * is <b>read back out of the rendered PDF</b> rather than captured from the
 * service. That is the difference between proving a code was issued and proving
 * the piece of paper the admin hands over actually works.</p>
 */
class AdminCredentialResetTest {

    private static final String TARGET_ID = "usr_target";
    private static final String CONTROL_ID = "usr_control";
    private static final String ADMIN_ID = "usr_admin";
    private static final String OLD_PASSWORD = "The-Old-Choice-Cc9";
    private static final String IP = "203.0.113.10";
    private static final String ROOT = "https://tb.example.com";

    /**
     * The same shape the audit writer uses — an audit summary carrying an
     * {@code Instant} must serialize, not blow up, or a "does not contain the
     * code" assertion could never run at all.
     */
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final SlipActor ADMIN = new SlipActor(ADMIN_ID, "Example Admin", true);
    private static final SlipActor TEACHER = new SlipActor("usr_teacher", "Teacher Wei Chen", false);

    /** §2.5's printed grouping, in Crockford's data alphabet: {@code HKTZ4-9QP2M}. */
    private static final Pattern PRINTED_CODE = Pattern.compile(
            "\\b([" + ActivationCodeFormat.DATA_ALPHABET + "]{5}-["
                    + ActivationCodeFormat.CHECK_ALPHABET + "]{5})\\b");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final ActivationCodeRepository codeRepository = mock(ActivationCodeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private final Map<String, User> users = new HashMap<>();
    private final Map<String, ActivationCode> codesByHash = new HashMap<>();
    private final Map<String, ActivationCode> codesById = new HashMap<>();
    private final Map<String, Instant> redeemedInDatabase = new HashMap<>();

    private UserServiceImpl userService;
    private ActivationServiceImpl activationService;
    private CredentialSlipServiceImpl slipService;

    private User target;
    private User control;

    private ListAppender<ILoggingEvent> logs;
    private Logger timebankLogger;

    @BeforeEach
    void setUp() {
        target = activeUser(TARGET_ID, "31415926", "Example Student");
        control = activeUser(CONTROL_ID, "27182818", "Example Control");
        users.put(ADMIN_ID, activeUser(ADMIN_ID, "admin001", "Example Admin"));

        when(userRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0, String.class))));
        when(userRepository.findByUsername(anyString())).thenAnswer(inv -> users.values().stream()
                .filter(u -> u.getUsername().equals(inv.getArgument(0, String.class)))
                .findFirst());
        when(codeRepository.save(any(ActivationCode.class))).thenAnswer(inv -> {
            ActivationCode row = inv.getArgument(0);
            codesByHash.put(row.getCodeHash(), row);
            codesById.put(row.getId(), row);
            return row;
        });
        when(codeRepository.findByCodeHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(codesByHash.get(inv.getArgument(0, String.class))));
        when(codeRepository.deleteUnredeemedFor(anyString())).thenAnswer(inv -> {
            String userId = inv.getArgument(0, String.class);
            List<ActivationCode> doomed = codesById.values().stream()
                    .filter(c -> c.getUserId().equals(userId) && !redeemedInDatabase.containsKey(c.getId()))
                    .toList();
            doomed.forEach(c -> {
                codesById.remove(c.getId());
                codesByHash.remove(c.getCodeHash());
            });
            return doomed.size();
        });
        when(codeRepository.markRedeemed(anyString(), any(Instant.class))).thenAnswer(inv -> {
            String id = inv.getArgument(0, String.class);
            Instant now = inv.getArgument(1);
            if (redeemedInDatabase.containsKey(id)) {
                return 0;
            }
            redeemedInDatabase.put(id, now);
            ReflectionTestUtils.setField(codesById.get(id), "redeemedAt", now);
            return 1;
        });

        userService = new UserServiceImpl(userRepository, roleRepository, passwordEncoder, auditService,
                mock(FeatureFlagService.class), mock(StudentService.class),
                new StudentUsernameGenerator(userRepository, () -> 10000001), 5, 15, 480);
        activationService = new ActivationServiceImpl(codeRepository, userRepository, passwordEncoder,
                auditService, new ActivationThrottle(1_000, 1_000), 7);
        slipService = new CredentialSlipServiceImpl(mock(StudentService.class),
                mock(AcademicStructureService.class), userService, activationService, auditService, ROOT);

        timebankLogger = (Logger) LoggerFactory.getLogger("cn.timebank");
        logs = new ListAppender<>();
        logs.start();
        timebankLogger.addAppender(logs);
        timebankLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        timebankLogger.detachAppender(logs);
    }

    // ------------------------------------------------------------------ (a)

    /**
     * §2.4, the whole point of §3.7: <b>a reset generates no password.</b>
     * Asserted on the account first — a null hash means nothing was generated
     * and so nothing could have been returned — and then on the serialized
     * response, because "we don't put it in the DTO" is a claim about an
     * intermediate object and the wire is what an admin actually receives.
     *
     * <p>The field-name sweep is what survives a revert: reinstating
     * {@code AdminResetPasswordResponse(username, temporaryPassword)} puts a
     * {@code password}-named field back into this JSON, whichever layer it is
     * added at.</p>
     */
    @Test
    void an_admin_reset_generates_no_password_and_returns_none() throws IOException {
        ResetSlip reset = slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThat(target.getPasswordHash())
                .as("no password is generated server-side, so there is no plaintext to leak")
                .isNull();
        assertThat(target.hasUsableCredential()).isFalse();

        String serialized = JSON.writeValueAsString(reset);
        assertThat(fieldNamesOf(serialized))
                .as("no field of the reset response may be a password")
                .noneMatch(name -> name.toLowerCase().contains("password"));
        assertThat(serialized)
                .as("nor a bcrypt hash, which would be the same mistake one step removed")
                .doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$");

        // Control: the response really does carry credential material, so the
        // assertions above are discriminating rather than testing an empty
        // body. The printed code hashes to the row that was just stored, which
        // is what makes it the account's live credential and not decoration.
        assertThat(codesByHash).containsKey(sha256Hex(codeOn(reset)));
    }

    /**
     * The audit row records the event, never the credential. Non-vacuous by the
     * same control as above: the code really was minted, and really is in the
     * document, so a row that contained it would be found.
     */
    @Test
    void the_audit_rows_record_the_reset_without_any_credential_material() throws IOException {
        String code = codeOn(slipService.issueForReset(TARGET_ID, ADMIN, "en"));

        List<AuditEntry> rows = auditRows();
        assertThat(rows).extracting(AuditEntry::action)
                .as("the credential clear and the issuance are each audited where they happen")
                .containsExactly("user.adminResetCredential", "user.issueActivationCode");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.actorId()).isEqualTo(ADMIN_ID);
            assertThat(row.resourceType()).isEqualTo("user");
            assertThat(row.resourceId()).isEqualTo(TARGET_ID);
        });

        String serialized = JSON.writeValueAsString(rows);
        assertThat(serialized).doesNotContain(code);
        assertThat(serialized).doesNotContain(codesById.values().iterator().next().getCodeHash());
        assertThat(serialized).doesNotContain("$2a$");
        // The row has to be readable a year later: "this account had a password
        // and now does not" is the event, and it is stated, not implied.
        assertThat(serialized).contains("\"hasPassword\":true").contains("\"hasPassword\":false");
    }

    /**
     * The code must not reach a log line either. The canary proves the appender
     * is attached and capturing — without it a silently detached appender would
     * make the real assertion pass for the wrong reason.
     */
    @Test
    void nothing_logs_the_code_or_a_hash() throws IOException {
        ResetSlip reset = slipService.issueForReset(TARGET_ID, ADMIN, "en");
        String code = codeOn(reset);
        LoggerFactory.getLogger("cn.timebank.canary").warn("canary-line");

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).as("the appender is attached and capturing").contains("canary-line");
        assertThat(lines).noneMatch(line -> line.contains(code)
                || line.contains(ActivationCodeFormat.group(code))
                || line.contains(sha256Hex(code))
                || line.contains("$2a$"));
    }

    // ------------------------------------------------------------------ (b)

    /**
     * A reset that leaves the old password working is not a reset. Run through
     * the real {@link DaoAuthenticationProvider} the login endpoint uses, with
     * an untouched account presenting <em>the same password string</em> as the
     * control: without it, a harness that rejected everything would make the
     * first half pass while proving nothing.
     */
    @Test
    void the_old_password_stops_working_while_an_untouched_account_still_signs_in() {
        slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThatThrownBy(() -> authenticate(target.getUsername(), OLD_PASSWORD))
                .as("the password the user had before the reset must stop working")
                .isInstanceOf(DisabledException.class);

        assertThat(authenticate(control.getUsername(), OLD_PASSWORD).isAuthenticated())
                .as("control: the same password on an account that was not reset still authenticates")
                .isTrue();
    }

    /** §2.6/§3.6: the reset account is pending, not locked and not deactivated. */
    @Test
    void the_reset_account_is_pending_activation() {
        slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThat(target.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(target.getStatus()).isNotIn(UserStatus.LOCKED, UserStatus.INACTIVE, UserStatus.ACTIVE);
        assertThat(target.isMustChangePassword()).isFalse();
        assertThat(target.getLockedUntil()).isNull();
        assertThat(target.getFailedLoginAttempts()).isZero();
    }

    /**
     * The most common reason a reset is asked for at all. A lockout must not
     * survive it, or the reset hands back an account that still refuses the new
     * password for another fifteen minutes.
     */
    @Test
    void a_locked_out_account_can_be_reset_and_comes_back_unlocked() {
        target.setStatus(UserStatus.LOCKED);
        target.setFailedLoginAttempts(5);
        target.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));

        slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThat(target.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(target.isLocked()).isFalse();
    }

    // ------------------------------------------------------------------ (c)

    /**
     * §2.5 and §2.7 together: exactly one code is usable afterwards, it is the
     * one on the printed slip, and redeeming it is what sets the new password.
     *
     * <p>The code is scraped out of the PDF, so this fails if the document ever
     * prints something other than the code that was actually issued.</p>
     */
    @Test
    void redeeming_the_slips_code_sets_a_new_password_and_reactivates() throws IOException {
        ResetSlip reset = slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThat(codesById).as("exactly one code exists for this account").hasSize(1);
        assertThat(redeemedInDatabase).isEmpty();

        String printed = printedCodeIn(pdfTextOf(reset));
        assertThat(codesByHash).containsKey(sha256Hex(printed.replace("-", "")));
        activationService.redeem(printed, "Chosen-By-The-User-1", IP);

        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(target.hasUsableCredential()).isTrue();
        assertThat(passwordEncoder.matches("Chosen-By-The-User-1", target.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, target.getPasswordHash())).isFalse();
        assertThat(authenticate(target.getUsername(), "Chosen-By-The-User-1").isAuthenticated()).isTrue();

        assertThatThrownBy(() -> activationService.redeem(printed, "A-Second-Go-22", IP))
                .as("single use: the slip works once")
                .isInstanceOf(ApiException.class);
    }

    /**
     * §2.7 applied to a reset: whatever code the account was holding — a slip
     * from the class batch, or an earlier reset — stops working. Two live codes
     * for one account would mean the lost slip still opened it.
     */
    @Test
    void an_earlier_outstanding_code_is_retired_by_the_reset() throws IOException {
        String earlier = issueDirectly();

        ResetSlip reset = slipService.issueForReset(TARGET_ID, ADMIN, "en");
        String printed = printedCodeIn(pdfTextOf(reset));

        assertThat(printed).isNotEqualTo(ActivationCodeFormat.group(earlier));
        assertThatThrownBy(() -> activationService.redeem(earlier, "Should-Not-Work-44", IP))
                .isInstanceOf(ApiException.class);
        assertThat(target.hasUsableCredential()).isFalse();

        // Control: the replacement does work, so the assertion above is about
        // the old code specifically and not about redemption being broken.
        activationService.redeem(printed, "Chosen-By-The-User-1", IP);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // The companion test that used to sit here is gone with its subject. It
    // guarded the one interaction §3.7 created with the *other* credential
    // mechanism, {@code password_reset_token}: a token minted for a just-reset
    // account would have set a password on it while leaving it {@code
    // PENDING_ACTIVATION}, unable to log in either way and invisible to §3.6's
    // sweep. That mechanism no longer exists (issue 089), so the guard it
    // tested no longer exists either — the hazard is closed by removal rather
    // than by a branch, which is the stronger form. That there is now exactly
    // one way to set a password is asserted directly, on the shipped source, by
    // {@code cn.timebank.architecture.NoSelfServicePasswordRecoveryTest}.

    // --------------------------------------------------------- the refusals

    /**
     * §2.10, D-10: "issue, reissue and reset are all admin". The refusal comes
     * before anything is cleared — a 403 returned after the credential was
     * wiped would have locked the user out anyway.
     */
    @Test
    void a_non_admin_is_refused_and_clears_nothing() {
        assertThatThrownBy(() -> slipService.issueForReset(TARGET_ID, TEACHER, "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FORBIDDEN);

        assertThat(target.hasUsableCredential()).isTrue();
        assertThat(codesById).isEmpty();
    }

    /**
     * An admin resetting their own account can only be doing it while signed
     * in, so they have not forgotten anything — and letting them clear their
     * own password stakes their way back in on a PDF download completing, with
     * nobody else able to issue them a slip.
     */
    @Test
    void an_admin_cannot_reset_their_own_account() {
        assertThatThrownBy(() -> slipService.issueForReset(ADMIN_ID, ADMIN, "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.STATE_CONFLICT);

        assertThat(users.get(ADMIN_ID).hasUsableCredential()).isTrue();
        assertThat(codesById).isEmpty();
    }

    /**
     * Deactivation is deliberate. A reset would move the account to
     * {@code PENDING_ACTIVATION} and hand out a redeemable code, quietly
     * undoing it — {@code ActivationServiceImpl.redeem} refuses {@code
     * INACTIVE} for the same reason.
     */
    @Test
    void a_deactivated_account_cannot_be_reset_into_activation() {
        target.setStatus(UserStatus.INACTIVE);

        assertThatThrownBy(() -> slipService.issueForReset(TARGET_ID, ADMIN, "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.STATE_CONFLICT);

        assertThat(target.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(codesById).isEmpty();
    }

    @Test
    void an_unknown_user_is_not_found() {
        assertThatThrownBy(() -> slipService.issueForReset("usr_nope", ADMIN, "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------- the document

    /**
     * §3.7: "one flow, one document format". The reset slip is the class
     * batch's slip — same labels, same provenance line, same language
     * resolution — not a second layout that can drift.
     */
    @Test
    void the_reset_slip_is_the_same_document_in_the_requested_language() throws IOException {
        ResetSlip english = slipService.issueForReset(TARGET_ID, ADMIN, "en");
        String englishText = pdfTextOf(english);
        assertThat(englishText).contains("Activation code").contains("Issued by").contains("Example Admin");
        assertThat(englishText).contains(target.getDisplayName()).contains(target.getUsername());
        assertThat(englishText).doesNotContain("激活码");

        String chineseText = pdfTextOf(slipService.issueForReset(TARGET_ID, ADMIN, "zh-CN"));
        assertThat(chineseText).contains("激活码").doesNotContain("Activation code");

        try (PDDocument pdf = Loader.loadPDF(english.document().pdf())) {
            assertThat(pdf.getNumberOfPages()).as("one account, one slip, one page").isEqualTo(1);
        }
        assertThat(english.document().slipCount()).isEqualTo(1);
    }

    /**
     * §2.8's filename rule: a reset slip lands in the same Downloads folder and
     * the same mail thread as a class batch, so it must not be the one that
     * names a person.
     */
    @Test
    void the_filename_identifies_nobody() throws IOException {
        ResetSlip reset = slipService.issueForReset(TARGET_ID, ADMIN, "en");

        assertThat(reset.document().filename()).startsWith("activation-slip-").endsWith(".pdf");
        assertThat(reset.document().filename())
                .doesNotContain(TARGET_ID)
                .doesNotContain(target.getUsername())
                .doesNotContain(target.getDisplayName())
                .doesNotContain(codeOn(reset));
    }

    /** The username travels back so the web layer can revoke sessions without a second lookup. */
    @Test
    void the_response_carries_the_username_the_sessions_are_keyed_by() {
        assertThat(slipService.issueForReset(TARGET_ID, ADMIN, "en").username())
                .isEqualTo(target.getUsername());
    }

    // ------------------------------------------------------------- helpers

    private User activeUser(String id, String username, String displayName) {
        User user = new User(id, username, passwordEncoder.encode(OLD_PASSWORD), displayName,
                null, "zh-CN", Instant.now());
        user.addRole(new Role("role_student", "student", "Student", 720, Instant.now()));
        users.put(id, user);
        return user;
    }

    /** Runs the real Spring Security credential check the login endpoint runs. */
    private Authentication authenticate(String username, String presentedPassword) {
        UserDetailsService userDetailsService = name -> userService.findByUsernameForAuth(name)
                .map(CookieSessionUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + name));
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider.authenticate(new UsernamePasswordAuthenticationToken(username, presentedPassword));
    }

    /** A code issued outside the reset path, standing in for a slip already handed out. */
    private String issueDirectly() {
        return activationService.issue(TARGET_ID, ADMIN_ID).code();
    }

    private static String pdfTextOf(ResetSlip reset) throws IOException {
        try (PDDocument document = Loader.loadPDF(reset.document().pdf())) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * The plaintext code, read back off the printed slip. After issuance this
     * really is the only place it exists — the database holds a SHA-256 of it
     * and nothing else does — so every test that needs the value gets it the
     * same way the student does.
     */
    private static String codeOn(ResetSlip reset) throws IOException {
        return printedCodeIn(pdfTextOf(reset)).replace("-", "");
    }

    private static String printedCodeIn(String pdfText) {
        Matcher matcher = PRINTED_CODE.matcher(pdfText);
        assertThat(matcher.find()).as("the slip prints a grouped activation code").isTrue();
        return matcher.group(1);
    }

    private List<AuditEntry> auditRows() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService, atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }

    /** Every JSON field name in the document, at any depth. */
    private static List<String> fieldNamesOf(String json) throws IOException {
        List<String> names = new ArrayList<>();
        collectFieldNames(JSON.readTree(json), names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                into.add(name);
                collectFieldNames(node.get(name), into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, into));
        }
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
