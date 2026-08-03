package cn.timebank.proposal.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.notification.infrastructure.NotificationRepository;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.infrastructure.ProjectRepository;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalRequests.CreateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.infrastructure.EventProposalRepository;
import cn.timebank.proposal.infrastructure.ProposalReviewEventRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4.11's exit criteria against real MySQL: approval generates exactly one
 * project with `approvedProjectId` backfilled, a duplicate `Idempotency-Key`
 * replays instead of double-generating, and a forced failure during project
 * generation leaves the proposal un-approved with no orphan project
 * (invariant 6).
 */
class ProposalApprovalTransactionIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ProposalService proposalService;
    @Autowired
    private ProposalApprovalTransaction approvalTransaction;
    @Autowired
    private EventProposalRepository eventProposalRepository;
    @Autowired
    private ProposalReviewEventRepository reviewEventRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void approvalGeneratesExactlyOneProjectAndBackfillsApprovedProjectId() {
        StudentDetailDto student = createStudent();
        ProposalDto submitted = createAndSubmitProposal(student);

        ProposalDto approved = proposalService.decide(submitted.id(),
                new DecideRequest("approve", "GOOD", "Looks safe", submitted.version()),
                staffAuth("admin"), "idem-propapprove-" + submitted.id(), "zh-CN");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedProjectId()).isNotNull();
        assertThat(projectRepository.findById(approved.approvedProjectId())).isPresent();
        assertThat(reviewEventRepository.findByEventProposalIdOrderByCreatedAtAsc(submitted.id())).hasSize(1);
        assertThat(notificationRepository.countByRelatedResourceTypeAndRelatedResourceId("eventProposal", submitted.id()))
                .isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyReplaysInsteadOfGeneratingASecondProject() {
        StudentDetailDto student = createStudent();
        ProposalDto submitted = createAndSubmitProposal(student);
        String key = "idem-propdup-" + submitted.id();
        DecideRequest request = new DecideRequest("approve", "GOOD", null, submitted.version());

        ProposalDto first = proposalService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");
        ProposalDto replay = proposalService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");

        assertThat(replay.approvedProjectId()).isEqualTo(first.approvedProjectId());
        assertThat(reviewEventRepository.findByEventProposalIdOrderByCreatedAtAsc(submitted.id())).hasSize(1);
    }

    @Test
    void aForcedFailureDuringProjectGenerationLeavesNoApprovedProposalAndNoOrphanProject() {
        StudentDetailDto student = createStudent();
        ProposalDto submitted = createAndSubmitProposal(student);

        ProjectService failingProjectService = Mockito.mock(ProjectService.class, inv -> {
            throw new RuntimeException("forced failure: project generation");
        });
        Object original = ReflectionTestUtils.getField(approvalTransaction, "projectService");
        ReflectionTestUtils.setField(approvalTransaction, "projectService", failingProjectService);
        try {
            assertThatThrownBy(() -> proposalService.decide(submitted.id(),
                    new DecideRequest("approve", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-propfail-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            ReflectionTestUtils.setField(approvalTransaction, "projectService", original);
        }

        var reread = eventProposalRepository.findById(submitted.id()).orElseThrow();
        assertThat(reread.getStatus().name()).isEqualTo("SUBMITTED");
        assertThat(reread.getApprovedProjectId()).isNull();
        assertThat(reviewEventRepository.findByEventProposalIdOrderByCreatedAtAsc(submitted.id())).isEmpty();
    }

    @Test
    void rejectDoesNotGenerateAProject() {
        StudentDetailDto student = createStudent();
        ProposalDto submitted = createAndSubmitProposal(student);

        ProposalDto rejected = proposalService.decide(submitted.id(),
                new DecideRequest("reject", "NOT_FEASIBLE", "Not enough supervision", submitted.version()),
                staffAuth("admin"), "idem-propreject-" + submitted.id(), "zh-CN");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.approvedProjectId()).isNull();
    }

    private ProposalDto createAndSubmitProposal(StudentDetailDto student) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        CreateProposalRequest request = new CreateProposalRequest(
                List.of(), "Campus Cleanup Drive", "Improve campus cleanliness", "A student-led cleanup effort",
                "A cleaner campus", "cat_campus", start, end, "Main Quad", "onsite", "All grades", 20,
                "Sorting and collecting litter", "usr_test_admin", null, null,
                "Adult supervision by a teacher at all times; first-aid kit on site",
                "Gloves and trash bags", "Flyers posted in classrooms", 120, "Photos and a supervisor sign-off");
        ProposalDto draft = proposalService.createDraft(request, student.id(), student.id(), "zh-CN");
        return proposalService.submit(draft.id(), studentAuth(student.id()), "idem-propsubmit-" + draft.id(), "zh-CN");
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_proposal_" + n, "Proposal Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");
        createLinkedUser(student.id());
        return student;
    }

    private void createLinkedUser(String studentId) {
        UserDto user = userService.create(new CreateUserRequest(
                "proposal_user_" + studentId, "InitialPass123!", "Proposal User", null,
                Set.of("student"), "zh-CN"), "usr_test_admin");
        User entity = userRepository.findById(user.id()).orElseThrow();
        entity.setStudentId(studentId);
        userRepository.save(entity);
    }

    private Authentication studentAuth(String studentId) {
        return authFor("usr_" + studentId.substring(4), studentId, Set.of("student"));
    }

    private Authentication staffAuth(String role) {
        return authFor("usr_" + role + "_actor", null, Set.of(role));
    }

    private Authentication authFor(String userId, String studentId, Set<String> roleCodes) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", roleCodes, Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
