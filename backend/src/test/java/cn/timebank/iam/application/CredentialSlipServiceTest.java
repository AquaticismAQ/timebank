package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.ActivationService.IssuedCode;
import cn.timebank.iam.application.CredentialSlipService.SlipActor;
import cn.timebank.iam.application.CredentialSlipService.SlipDocument;
import cn.timebank.iam.application.UserService.StudentAccount;
import cn.timebank.iam.application.dto.CredentialSlipDtos.IssueSlipsRequest;
import cn.timebank.iam.application.dto.CredentialSlipDtos.SlipsPerPage;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §2.7, §2.10 and §3.4.
 *
 * <p>{@code ActivationService} is a behaving fake rather than a stub: it mints
 * a distinct plaintext code per user, exactly as the real one does. That is
 * what lets the audit test below search the recorded row for <em>the actual
 * codes this call issued</em> instead of for a string the test invented, which
 * would pass even if the service audited a real code it got from somewhere
 * else.</p>
 */
class CredentialSlipServiceTest {

    private static final String CLASS_ID = "cls_7a";
    private static final String ADMIN_ID = "usr_admin";
    private static final String ROOT = "https://tb.example.com";

    private static final SlipActor ADMIN = new SlipActor(ADMIN_ID, "Example Admin", true);
    private static final SlipActor TEACHER = new SlipActor("usr_teacher", "Teacher Wei Chen", false);

    private StudentService studentService;
    private AcademicStructureService academicStructureService;
    private UserService userService;
    private RecordingActivationService activationService;
    private AuditService auditService;
    private CredentialSlipServiceImpl service;

    @BeforeEach
    void setUp() {
        studentService = mock(StudentService.class);
        academicStructureService = mock(AcademicStructureService.class);
        userService = mock(UserService.class);
        activationService = new RecordingActivationService();
        auditService = mock(AuditService.class);
        service = new CredentialSlipServiceImpl(studentService, academicStructureService, userService,
                activationService, auditService, ROOT);

        when(academicStructureService.listClasses())
                .thenReturn(List.of(new ClassDto(CLASS_ID, "7-1", "Class 7-1", "grd_7", "ay_2026")));
    }

    // ---------------------------------------------------------------- §2.7

    /**
     * §2.7: "regeneration reissues codes for every still-pending member of the
     * batch — never for students who have already activated and chosen a
     * password. Losing five slips must not reset the twenty-five students who
     * were fine."
     *
     * <p>Asserted on what reaches {@code issueAll}, because that is the call
     * that mints a credential and retires the previous one. An activated
     * student appearing there would have had their working login quietly
     * replaced.</p>
     */
    @Test
    void anAlreadyActivatedStudentIsExcludedFromARegeneratedBatch() {
        roster(student("std_1", "Pending One"), student("std_2", "Activated Two"), student("std_3", "Pending Three"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION),
                account("std_2", UserStatus.ACTIVE),
                account("std_3", UserStatus.PENDING_ACTIVATION));

        SlipDocument document = service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        assertThat(activationService.issuedFor)
                .as("only the still-pending members of the batch get a new code")
                .containsExactly("usr_std_1", "usr_std_3");
        assertThat(document.slipCount()).isEqualTo(2);
    }

    /** An account whose window closed has still never had a password (§2.6) — it is issuable. */
    @Test
    void anExpiredNeverActivatedAccountIsStillIssuable() {
        roster(student("std_1", "Expired One"));
        accounts(account("std_1", UserStatus.ACTIVATION_EXPIRED));

        service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        assertThat(activationService.issuedFor).containsExactly("usr_std_1");
    }

    /**
     * A locked or deactivated account already has a credential; a new slip is
     * not the answer to either, and issuing one would reset a password the
     * student already chose.
     */
    @Test
    void lockedAndDeactivatedAccountsAreNotReissuedEither() {
        roster(student("std_1", "Locked One"), student("std_2", "Off Two"));
        accounts(account("std_1", UserStatus.LOCKED), account("std_2", UserStatus.INACTIVE));

        assertThatThrownBy(() -> service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.STATE_CONFLICT);
        assertThat(activationService.issuedFor).isEmpty();
    }

    /** A whole class that already activated is a 409, not an empty PDF. */
    @Test
    void aFullyActivatedClassIsRefusedRatherThanPrintedEmpty() {
        roster(student("std_1", "Activated One"));
        accounts(account("std_1", UserStatus.ACTIVE));

        assertThatThrownBy(() -> service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already activated");
        assertThat(activationService.issuedFor).isEmpty();
    }

    /**
     * §5's open gap: {@code POST /students} still creates no account. One
     * hand-added roster row without a login must not stop a class printing.
     */
    @Test
    void aRosterRowWithNoAccountIsSkippedRatherThanFailingTheBatch() {
        roster(student("std_1", "Pending One"), student("std_2", "No Account Two"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION));

        service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        assertThat(activationService.issuedFor).containsExactly("usr_std_1");
    }

    // --------------------------------------------------------------- §2.10

    /**
     * §2.10, D-9/D-10: teachers get no slip path at all, not even a scoped one
     * for their own class. "The slip is the one artefact in the system that
     * carries a working credential" — a data-scope bug on a reporting endpoint
     * leaks a list, the same bug here mints credentials.
     */
    @Test
    void aNonAdminIsRefused() {
        roster(student("std_1", "Pending One"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION));

        assertThatThrownBy(() -> service.issueForClass(request(SlipsPerPage.ONE), TEACHER, auth(), "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    /**
     * The refusal has to happen <em>before</em> anything is minted. A 403
     * returned after {@code issueAll} ran would still have invalidated every
     * slip the class was already holding.
     */
    @Test
    void aRefusedCallerMintsNothingAndReadsNoRoster() {
        roster(student("std_1", "Pending One"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION));

        assertThatThrownBy(() -> service.issueForClass(request(SlipsPerPage.ONE), TEACHER, auth(), "en"))
                .isInstanceOf(ApiException.class);

        assertThat(activationService.issuedFor).isEmpty();
        verifyNoInteractions(auditService);
        verify(studentService, never()).list(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------- audit hygiene

    /**
     * The repo requires every mutation to audit, and §3.4 names what the row
     * has to answer: who generated slips for which class, and when. The code
     * must reach none of it — not the row, not a log line, not an error.
     */
    @Test
    void theAuditRowNamesTheActorAndTheClassAndNeverCarriesACode() throws IOException {
        roster(student("std_1", "Pending One"), student("std_2", "Activated Two"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION), account("std_2", UserStatus.ACTIVE));

        service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(captor.capture());
        AuditEntry entry = captor.getValue();

        assertThat(entry.actorId()).isEqualTo(ADMIN_ID);
        assertThat(entry.resourceType()).isEqualTo("schoolClass");
        assertThat(entry.resourceId()).isEqualTo(CLASS_ID);
        assertThat(entry.action()).isEqualTo("user.issueCredentialSlips");

        String serialized = new ObjectMapper().writeValueAsString(entry);
        assertThat(activationService.issuedCodes)
                .as("the fake really did mint codes, so the check below is not vacuous")
                .isNotEmpty();
        for (String code : activationService.issuedCodes) {
            assertThat(serialized)
                    .as("plaintext activation code must never reach an audit row")
                    .doesNotContain(code);
        }
        // Belt and braces: the codes are in the streamed document, which is
        // where they are supposed to be and the only place.
        assertThat(pdfText(service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en").pdf()))
                .contains(ActivationCodeFormat.group(activationService.issuedCodes.getLast()));
    }

    /** The row has to distinguish "12 of 40 needed a slip" from "a class of 12". */
    @Test
    void theAuditRowRecordsHowManyStudentsWereSparedRatherThanJustTheSlipCount() throws IOException {
        roster(student("std_1", "Pending One"), student("std_2", "Activated Two"), student("std_3", "Activated Three"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION), account("std_2", UserStatus.ACTIVE),
                account("std_3", UserStatus.ACTIVE));

        service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(captor.capture());
        String after = new ObjectMapper().writeValueAsString(captor.getValue().after());
        assertThat(after).contains("\"rosterSize\":3");
        assertThat(after).contains("\"slipsIssued\":1");
        assertThat(after).contains("\"alreadyActivated\":2");
    }

    // --------------------------------------------------------- §2.8 layout

    /** §2.8's default: {@code slipsPerPage} omitted means one per page. */
    @Test
    void slipsPerPageDefaultsToOne() throws IOException {
        roster(student("std_1", "One"), student("std_2", "Two"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION), account("std_2", UserStatus.PENDING_ACTIVATION));

        SlipDocument document = service.issueForClass(
                new IssueSlipsRequest(CLASS_ID, null), ADMIN, auth(), "en");

        try (PDDocument pdf = Loader.loadPDF(document.pdf())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
        }
    }

    /** Two per page halves the sheet count — and is a per-request choice, not config. */
    @Test
    void twoPerPageIsHonouredFromTheRequest() throws IOException {
        roster(student("std_1", "One"), student("std_2", "Two"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION), account("std_2", UserStatus.PENDING_ACTIVATION));

        SlipDocument document = service.issueForClass(request(SlipsPerPage.TWO), ADMIN, auth(), "en");

        try (PDDocument pdf = Loader.loadPDF(document.pdf())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
    }

    /**
     * §2.9: "the screen must use the same words as the slip". The language the
     * controller resolved for the request is the language the paper prints in,
     * so an admin working in Chinese hands out Chinese slips.
     *
     * <p>Asserted at this level rather than only on the document builder
     * because the plumbing is the part that can silently break: the service
     * already had a {@code resolvedLanguage} parameter, used it for the roster
     * read, and dropped it on the floor on the way to the renderer. Both
     * directions are checked — a one-way test would pass against a renderer
     * hard-wired to either language.</p>
     */
    @Test
    void theSlipPrintsInTheLanguageTheRequestResolvedTo() throws IOException {
        roster(student("std_1", "Pending One"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION));

        String chinese = pdfText(service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "zh-CN").pdf());
        assertThat(chinese).contains("激活码").doesNotContain("Activation code");

        String english = pdfText(service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en").pdf());
        assertThat(english).contains("Activation code").doesNotContain("激活码");
    }

    /**
     * §2.8 rejected one file per student partly because "filenames would carry
     * student identifiers into the mail client, the Downloads folder and every
     * mail-server backup".
     */
    @Test
    void theFilenameCarriesTheClassAndTheDateButNoStudentIdentifier() {
        roster(student("std_1", "Pending One"));
        accounts(account("std_1", UserStatus.PENDING_ACTIVATION));

        SlipDocument document = service.issueForClass(request(SlipsPerPage.ONE), ADMIN, auth(), "en");

        assertThat(document.filename()).startsWith("activation-slips-7-1-").endsWith(".pdf");
        assertThat(document.filename()).doesNotContain("std_1").doesNotContain("usr_std_1");
        assertThat(document.filename()).doesNotContain(activationService.issuedCodes.getFirst());
    }

    /** An unknown class is a 404, before anything is minted. */
    @Test
    void anUnknownClassIsNotFound() {
        assertThatThrownBy(() -> service.issueForClass(
                new IssueSlipsRequest("cls_nope", SlipsPerPage.ONE), ADMIN, auth(), "en"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND);
        assertThat(activationService.issuedFor).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private static IssueSlipsRequest request(SlipsPerPage slipsPerPage) {
        return new IssueSlipsRequest(CLASS_ID, slipsPerPage);
    }

    private static Authentication auth() {
        return mock(Authentication.class);
    }

    private void roster(StudentListItemDto... students) {
        Page<StudentListItemDto> page = new PageImpl<>(List.of(students), Pageable.unpaged(), students.length);
        // The `eq(null)` before `anyString()` is the progress filter (issue 159):
        // a credential-slip roster is every active student in the class, never a
        // completion bucket.
        when(studentService.list(any(), any(), eq(null), eq(CLASS_ID), eq("ACTIVE"), eq(null), eq(null), anyString()))
                .thenReturn(page);
    }

    private void accounts(StudentAccount... accounts) {
        when(userService.studentAccountsOf(anyList())).thenReturn(List.of(accounts));
    }

    private static StudentListItemDto student(String id, String displayName) {
        // Progress and balance are zeroed: a credential slip names a student and a
        // code, and nothing on it depends on how many hours they have served or
        // what their account holds (issue 159 added the fields for Student
        // Management's filter and sorts).
        return new StudentListItemDto(id, "SN-" + id, displayName, "grd_7", 7, CLASS_ID, "Class 7-1",
                "ACTIVE", 2029, 0L, 0, 0L, 0L);
    }

    private static StudentAccount account(String studentId, UserStatus status) {
        return new StudentAccount(studentId, "usr_" + studentId, "1000" + studentId.hashCode(), status.name());
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Mints a distinct, well-formed code per call and remembers both the users
     * it issued for and the plaintext it handed back — the two facts every
     * assertion above needs. Deliberately applies no status filter, mirroring
     * the real {@code issueAll}, so a service that forgot §2.7's selection rule
     * would sail straight through it.
     */
    private static final class RecordingActivationService implements ActivationService {

        private final List<String> issuedFor = new ArrayList<>();
        private final List<String> issuedCodes = new ArrayList<>();
        private int counter;

        @Override
        public IssuedCode issue(String userId, String issuedByUserId) {
            String code = ActivationCodeFormat.generate(bound -> (counter++ * 7 + userId.length()) % bound);
            issuedFor.add(userId);
            issuedCodes.add(code);
            return new IssuedCode(userId, "1000000" + issuedFor.size(), code,
                    Instant.parse("2026-09-17T09:30:00Z"), Instant.parse("2026-09-24T09:30:00Z"));
        }

        @Override
        public List<IssuedCode> issueAll(List<String> userIds, String issuedByUserId) {
            return userIds.stream().map(id -> issue(id, issuedByUserId)).toList();
        }

        @Override
        public void redeem(String rawCode, String newPassword, String clientIp) {
            throw new UnsupportedOperationException();
        }
    }
}
