package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.ActivationService.IssuedCode;
import cn.timebank.iam.application.CredentialSlipDocument.Slip;
import cn.timebank.iam.application.dto.CredentialSlipDtos.IssueSlipsRequest;
import cn.timebank.iam.application.dto.CredentialSlipDtos.SlipsPerPage;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 10-StudentIdentityAndOnboarding.md §3.4.
 *
 * <h2>The selection rule is here, on purpose</h2>
 *
 * <p>§2.7: regeneration reissues for every still-pending member of the batch
 * and <b>never</b> for a student who has already chosen a password — losing
 * five slips must not reset the twenty-five students who were fine.
 * {@code ActivationService.issueAll} deliberately applies no status filter,
 * because §3.7's admin reset legitimately issues a code for an already-active
 * account. So the filter belongs to the caller assembling the batch, which is
 * {@link #issuableAccounts} below, and nowhere else.</p>
 *
 * <h2>What never reaches a log, an audit row or an error message</h2>
 *
 * <p>The plaintext codes exist here for the length of one method call and go
 * into the PDF bytes, full stop. The audit row records the class, the actor and
 * the user ids that got a slip — never a code, never a code hash, and never the
 * document. {@code IssuedCode} and {@link Slip} both redact their
 * {@code toString()} so an accidental {@code log.debug("{}", slip)} cannot undo
 * that, and no exception thrown from this class interpolates anything derived
 * from a code.</p>
 */
@Service
class CredentialSlipServiceImpl implements CredentialSlipService {

    /**
     * §2.7. {@code ACTIVATION_EXPIRED} is issuable for the same reason
     * {@code PENDING_ACTIVATION} is: neither has ever had a password, and §2.6
     * describes an expired account as "recoverable by reissuing a code".
     * {@code ACTIVE} is the one this list exists to exclude; {@code LOCKED} and
     * {@code INACTIVE} are excluded too, because both belong to an account that
     * already has a credential and neither is fixed by printing a new slip
     * (an admin unlock and an admin reactivation are the respective answers).
     */
    private static final Set<String> ISSUABLE_STATUSES =
            Set.of(UserStatus.PENDING_ACTIVATION.name(), UserStatus.ACTIVATION_EXPIRED.name());

    /**
     * A class, not a school. Sized well above any real class so it never bites
     * in practice, and enforced as a hard refusal rather than a silent trim —
     * quietly printing the first 200 of 250 students would hand a head teacher
     * a stack that looks complete and is not.
     */
    private static final int MAX_CLASS_SIZE = 200;

    private static final DateTimeFormatter FILENAME_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final StudentService studentService;
    private final AcademicStructureService academicStructureService;
    private final UserService userService;
    private final ActivationService activationService;
    private final AuditService auditService;
    private final String siteRootUrl;

    CredentialSlipServiceImpl(StudentService studentService, AcademicStructureService academicStructureService,
                               UserService userService, ActivationService activationService, AuditService auditService,
                               @Value("${timebank.auth.activation.site-root-url}") String siteRootUrl) {
        this.studentService = studentService;
        this.academicStructureService = academicStructureService;
        this.userService = userService;
        this.activationService = activationService;
        this.auditService = auditService;
        this.siteRootUrl = stripTrailingSlash(siteRootUrl);
    }

    /**
     * The render happens <b>inside</b> the transaction on purpose, even though
     * it is the slow part. Issuing retires whatever code each student held
     * before, so a render that failed after the commit would leave a class with
     * every old slip dead and no new one printed — recoverable only by issuing
     * again. Rolling the whole thing back instead means a failed download
     * changes nothing.
     */
    @Override
    @Transactional
    public SlipDocument issueForClass(IssueSlipsRequest request, SlipActor actor, Authentication auth,
                                       String resolvedLanguage) {
        if (!actor.isAdmin()) {
            // §2.10: a plain role check. Not "the caller's scope does not cover
            // this class" — there is no scoped path to fall back to, and saying
            // so would imply one exists.
            throw ApiException.forbidden("Only an administrator can generate activation slips.");
        }

        ClassDto schoolClass = academicStructureService.listClasses().stream()
                .filter(c -> c.id().equals(request.classId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Class not found."));

        List<StudentListItemDto> roster = roster(request.classId(), auth, resolvedLanguage);
        List<UserService.StudentAccount> issuable = issuableAccounts(roster);
        if (issuable.isEmpty()) {
            throw ApiException.stateConflict(
                    "Every student in this class has already activated their account, "
                            + "so there is nothing to print.");
        }

        Map<String, String> displayNameByStudentId = new HashMap<>();
        roster.forEach(s -> displayNameByStudentId.put(s.id(), s.displayName()));
        Map<String, String> studentIdByUserId = new HashMap<>();
        issuable.forEach(a -> studentIdByUserId.put(a.userId(), a.studentId()));

        List<IssuedCode> codes = activationService.issueAll(
                issuable.stream().map(UserService.StudentAccount::userId).toList(), actor.userId());

        List<Slip> slips = new ArrayList<>(codes.size());
        for (IssuedCode code : codes) {
            slips.add(new Slip(
                    displayNameByStudentId.get(studentIdByUserId.get(code.userId())),
                    code.username(),
                    code.code(),
                    activationUrl(code.code()),
                    siteRootUrl,
                    actor.displayName(),
                    code.issuedAt(),
                    code.expiresAt()));
        }

        SlipsPerPage slipsPerPage = request.resolvedSlipsPerPage();
        byte[] pdf = CredentialSlipDocument.render(slips, slipsPerPage);

        // §3.4: "audited — who generated slips for which class, and when".
        // `when` is the audit row's own timestamp. The summary carries the ids
        // that got a slip so the row answers "for whom" too, and carries the
        // skipped count so a later reader can tell a 12-slip batch from a
        // 40-student class apart from a 12-student class.
        auditService.record(new AuditEntry(actor.userId(), "users.manage", "user.issueCredentialSlips",
                "schoolClass", schoolClass.id(), null,
                new SlipBatchSummary(roster.size(), slips.size(), roster.size() - slips.size(),
                        slipsPerPage.value(), codes.stream().map(IssuedCode::userId).toList()),
                null));

        return new SlipDocument(pdf, filename(schoolClass, codes.getFirst()), slips.size());
    }

    private List<StudentListItemDto> roster(String classId, Authentication auth, String resolvedLanguage) {
        Page<StudentListItemDto> page = studentService.list(
                PageRequest.of(0, MAX_CLASS_SIZE, Sort.by("displayName")), auth,
                null, classId, "ACTIVE", null, resolvedLanguage);
        if (page.getTotalElements() > MAX_CLASS_SIZE) {
            throw ApiException.invalidRequest(
                    "This class has more than " + MAX_CLASS_SIZE + " active students, "
                            + "which is more than one slip batch covers.");
        }
        return page.getContent();
    }

    /**
     * §2.7's selection rule, in Java where a unit test can reach it. Roster
     * order is preserved (the page is sorted by display name) so a printed
     * stack comes out in the order a head teacher reads a register — the
     * account lookup returns rows in whatever order the database chose.
     */
    private List<UserService.StudentAccount> issuableAccounts(List<StudentListItemDto> roster) {
        Map<String, UserService.StudentAccount> byStudentId = new LinkedHashMap<>();
        userService.studentAccountsOf(roster.stream().map(StudentListItemDto::id).toList())
                .forEach(a -> byStudentId.put(a.studentId(), a));

        List<UserService.StudentAccount> issuable = new ArrayList<>();
        for (StudentListItemDto student : roster) {
            UserService.StudentAccount account = byStudentId.get(student.id());
            // A roster row with no account at all is skipped rather than
            // failing the batch: §5 records that `POST /students` still
            // provisions no login, so a hand-added student legitimately has
            // none yet, and one such row must not stop a class printing.
            if (account != null && ISSUABLE_STATUSES.contains(account.status())) {
                issuable.add(account);
            }
        }
        return issuable;
    }

    /**
     * §2.5/§2.9: the QR's deep link — the same code the slip prints in text,
     * wrapped so scanning is one tap. One secret, two delivery forms. The shape
     * matches what the frontend's {@code /activate} route already reads
     * ({@code ActivationPage} prefills from {@code ?code=}).
     */
    private String activationUrl(String code) {
        return siteRootUrl + "/activate?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }

    /**
     * §2.8 rejected one file per student partly because "filenames would carry
     * student identifiers into the mail client, the Downloads folder and every
     * mail-server backup". So this one carries the class and the date and
     * nothing about a person. The class code is reduced to ASCII rather than
     * escaped, because a {@code Content-Disposition} filename is read by
     * every mail gateway and file manager in the chain and none of them agree
     * on encoding.
     */
    private String filename(ClassDto schoolClass, IssuedCode anySlip) {
        String tag = asciiSlug(schoolClass.code());
        if (tag.isEmpty()) {
            tag = asciiSlug(schoolClass.id());
        }
        return "activation-slips-" + tag + "-" + FILENAME_DATE.format(anySlip.issuedAt()) + ".pdf";
    }

    private static String asciiSlug(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(c);
            } else if (c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Compact audit summary (Design.md §13). No code, no code hash, no document. */
    private record SlipBatchSummary(int rosterSize, int slipsIssued, int alreadyActivated,
                                     int slipsPerPage, List<String> userIds) {
    }
}
