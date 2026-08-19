package cn.timebank.proposal.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.notification.infrastructure.NotificationRepository;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.infrastructure.ProjectRepository;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalRequests.CreateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.infrastructure.EventProposalRepository;
import cn.timebank.proposal.infrastructure.ProposalReviewEventRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private RoleService roleService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

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

    @Test
    void listSupportsStatusFilterAndTitleSearch() {
        StudentDetailDto student = createStudent();
        ProposalDto submitted = createAndSubmitProposal(student, "Search Filter Canary Cleanup");

        // status filter narrows results to the submitted proposal's status
        Page<ProposalDto> byStatus = proposalService.list(PageRequest.of(0, 50), staffAuth("admin"),
                "SUBMITTED", null, null, "zh-CN");
        assertThat(byStatus.getContent()).extracting(ProposalDto::id).contains(submitted.id());
        assertThat(byStatus.getContent()).allMatch(p -> p.status().equals("SUBMITTED"));

        Page<ProposalDto> byOtherStatus = proposalService.list(PageRequest.of(0, 50), staffAuth("admin"),
                "APPROVED", null, null, "zh-CN");
        assertThat(byOtherStatus.getContent()).extracting(ProposalDto::id).doesNotContain(submitted.id());

        // search matches the title
        Page<ProposalDto> bySearch = proposalService.list(PageRequest.of(0, 50), staffAuth("admin"),
                null, null, "Canary", "zh-CN");
        assertThat(bySearch.getContent()).extracting(ProposalDto::id).contains(submitted.id());

        // an unmatched search returns empty
        Page<ProposalDto> byUnmatchedSearch = proposalService.list(PageRequest.of(0, 50), staffAuth("admin"),
                null, null, "NoSuchProposalTitleZZZ", "zh-CN");
        assertThat(byUnmatchedSearch.getContent()).isEmpty();
    }

    /**
     * `APIContract.md` §11 acceptance scenario 8 described {@code teacher}
     * as grade-scoped — no longer true after 07-RoleFinetune.md §2.1 made
     * {@code teacher} a {@code hasFullDataScope()} role like {@code admin}/
     * {@code sysadmin}. This test used to assert the out-of-scope proposal
     * was invisible; that described the pre-07-RoleFinetune behavior §2.1
     * deliberately changed, not a regression to guard against.
     */
    @Test
    void scopedTeacherSeesBothInScopeAndOutOfScopeProposalsSinceScopeNoLongerRestrictsTeachers() {
        StudentDetailDto inScopeStudent = createStudent(); // grade_6/class_6A
        StudentDetailDto outOfScopeStudent = createStudentInGrade9();
        ProposalDto inScopeProposal = createAndSubmitProposal(inScopeStudent, "In-Scope Cleanup");
        ProposalDto outOfScopeProposal = createAndSubmitProposal(outOfScopeStudent, "Out-Of-Scope Cleanup");

        var teacher = userService.create(new CreateUserRequest(
                "proposal_scope_teacher_" + OpaqueIds.generate("u"), "InitialPass123!", "Scope Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), teacher.id(),
                "eventProposals", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", Instant.now()));
        Authentication teacherAuth = authFor(teacher.id(), null, Set.of("teacher"));

        assertThat(proposalService.getDetail(outOfScopeProposal.id(), teacherAuth, "zh-CN").id())
                .isEqualTo(outOfScopeProposal.id());
        assertThat(proposalService.getDetail(inScopeProposal.id(), teacherAuth, "zh-CN").id())
                .isEqualTo(inScopeProposal.id());
    }

    /**
     * The organization/co-organizer/supervisor validation added alongside
     * this test (`Docs/optimization/01-i3-partner-school-terminology.md`):
     * before this pass, all three were accepted unvalidated free text.
     */
    @Test
    void creatingAProposalWithAnExistingOrganizationIdSucceeds() {
        OrganizationDto org = organizationService.create(
                new CreateOrganizationRequest("Sunrise Shelter", "COMMUNITY", null, null, null, null, null), "usr_test_admin");
        StudentDetailDto student = createStudent();

        ProposalDto draft = proposalService.createDraft(
                requestWithOrganization(org.id(), null), student.id(), student.id(), "zh-CN");

        assertThat(draft.organizationId()).isEqualTo(org.id());
        assertThat(draft.organizationName()).isEqualTo("Sunrise Shelter");
    }

    @Test
    void creatingAProposalWithANewOrganizationNameCreatesAndLinksItInTheSameTransaction() {
        StudentDetailDto student = createStudent();

        ProposalDto draft = proposalService.createDraft(
                requestWithOrganization(null, "Riverside Library"), student.id(), student.id(), "zh-CN");

        assertThat(draft.organizationId()).isNotBlank();
        assertThat(draft.organizationName()).isEqualTo("Riverside Library");
        assertThat(organizationService.get(draft.organizationId()).name()).isEqualTo("Riverside Library");
    }

    @Test
    void creatingAProposalWithBothOrganizationIdAndNewOrganizationNameIsRejected() {
        OrganizationDto org = organizationService.create(
                new CreateOrganizationRequest("Ambiguous Org", "COMMUNITY", null, null, null, null, null), "usr_test_admin");
        StudentDetailDto student = createStudent();

        assertThatThrownBy(() -> proposalService.createDraft(
                requestWithOrganization(org.id(), "Also New Org"), student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("organizationId") && fe.code().equals("AMBIGUOUS")));
    }

    @Test
    void creatingAProposalWithAnUnknownOrganizationIdIsRejected() {
        StudentDetailDto student = createStudent();

        assertThatThrownBy(() -> proposalService.createDraft(
                requestWithOrganization("org_does_not_exist", null), student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("organizationId") && fe.code().equals("NOT_FOUND")));
    }

    @Test
    void creatingAProposalWithAnUnknownCoOrganizerIsRejected() {
        StudentDetailDto student = createStudent();

        assertThatThrownBy(() -> proposalService.createDraft(
                requestWithCoOrganizers(List.of("std_does_not_exist")), student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("coOrganizerStudentIds") && fe.code().equals("NOT_FOUND")));
    }

    @Test
    void creatingAProposalListingTheProposerAsACoOrganizerIsRejected() {
        StudentDetailDto student = createStudent();

        assertThatThrownBy(() -> proposalService.createDraft(
                requestWithCoOrganizers(List.of(student.id())), student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("coOrganizerStudentIds") && fe.code().equals("INVALID")));
    }

    @Test
    void creatingAProposalWithARealCoOrganizerSucceedsAndResolvesTheirName() {
        StudentDetailDto proposer = createStudent();
        StudentDetailDto coOrganizer = createStudent();

        ProposalDto draft = proposalService.createDraft(
                requestWithCoOrganizers(List.of(coOrganizer.id())), proposer.id(), proposer.id(), "zh-CN");

        assertThat(draft.coOrganizerStudentIds()).containsExactly(coOrganizer.id());
        assertThat(draft.coOrganizerNames()).containsExactly(coOrganizer.displayName());
    }

    @Test
    void creatingAProposalWithANonTeacherSupervisorIsRejected() {
        StudentDetailDto student = createStudent();
        UserDto nonTeacher = userService.create(new CreateUserRequest(
                "not_a_teacher_" + OpaqueIds.generate("u"), "InitialPass123!", "Not A Teacher", null,
                "student", "zh-CN"), "usr_test_admin");

        assertThatThrownBy(() -> proposalService.createDraft(
                requestWithSupervisor(nonTeacher.id()), student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("proposedSupervisorId") && fe.code().equals("NOT_FOUND")));
    }

    /**
     * 07-RoleFinetune.md §2.2: supervisor eligibility is permission-based
     * ({@code eventProposals.review}) now, not the literal {@code teacher}
     * role code — a brand-new custom role holding that permission must be
     * just as eligible as a real teacher, not only "not rejected as before."
     */
    @Test
    void creatingAProposalWithACustomRoleSupervisorHoldingEventProposalsReviewSucceeds() {
        StudentDetailDto student = createStudent();
        var customRole = roleService.createRole(new CreateRoleRequest(
                "supervisor_eligible_" + OpaqueIds.generate("r").substring(0, 10), "Supervisor-Eligible Custom Role", 480,
                Set.of("eventProposals.review")), "usr_test_admin");
        UserDto customSupervisor = userService.create(new CreateUserRequest(
                "custom_supervisor_" + OpaqueIds.generate("u"), "InitialPass123!", "Custom Supervisor",
                null, customRole.code(), "zh-CN"), "usr_test_admin");

        ProposalDto created = proposalService.createDraft(
                requestWithSupervisor(customSupervisor.id()), student.id(), student.id(), "zh-CN");

        assertThat(created.proposedSupervisorId()).isEqualTo(customSupervisor.id());
    }

    private CreateProposalRequest requestWithOrganization(String organizationId, String newOrganizationName) {
        return baseRequestBuilder(List.of(), organizationId, newOrganizationName);
    }

    private CreateProposalRequest requestWithCoOrganizers(List<String> coOrganizerStudentIds) {
        return baseRequestBuilder(coOrganizerStudentIds, null, null);
    }

    private CreateProposalRequest requestWithSupervisor(String proposedSupervisorId) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        return new CreateProposalRequest(
                List.of(), "Validation Test Proposal", "Objective", "Description",
                "Outcome", "cat_campus", start, end, "Main Quad", "onsite", "All grades", 20,
                "Tasks", proposedSupervisorId, null, null, null,
                "Safety plan", "Resources", "Recruitment plan", 120, "Evidence plan");
    }

    private CreateProposalRequest baseRequestBuilder(List<String> coOrganizerStudentIds, String organizationId, String newOrganizationName) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        return new CreateProposalRequest(
                coOrganizerStudentIds, "Validation Test Proposal", "Objective", "Description",
                "Outcome", "cat_campus", start, end, "Main Quad", "onsite", "All grades", 20,
                "Tasks", realTeacherId(), organizationId, newOrganizationName, null,
                "Safety plan", "Resources", "Recruitment plan", 120, "Evidence plan");
    }

    private StudentDetailDto createStudentInGrade9() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_proposal9_" + n, "Proposal Student " + n, null, "grade_9", "class_9A", "ay_2025_2026",
                2026, LocalDate.of(2025, 9, 1), null, null);
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");
        createLinkedUser(student.id());
        return student;
    }

    private ProposalDto createAndSubmitProposal(StudentDetailDto student) {
        return createAndSubmitProposal(student, "Campus Cleanup Drive");
    }

    private ProposalDto createAndSubmitProposal(StudentDetailDto student, String title) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        CreateProposalRequest request = new CreateProposalRequest(
                List.of(), title, "Improve campus cleanliness", "A student-led cleanup effort",
                "A cleaner campus", "cat_campus", start, end, "Main Quad", "onsite", "All grades", 20,
                "Sorting and collecting litter", realTeacherId(), null, null, null,
                "Adult supervision by a teacher at all times; first-aid kit on site",
                "Gloves and trash bags", "Flyers posted in classrooms", 120, "Photos and a supervisor sign-off");
        ProposalDto draft = proposalService.createDraft(request, student.id(), student.id(), "zh-CN");
        return proposalService.submit(draft.id(), studentAuth(student.id()), "idem-propsubmit-" + draft.id(), "zh-CN");
    }

    /** `proposedSupervisorId` is validated (`Docs/optimization/01-i3-partner-school-terminology.md`) — must be a real active `teacher`. */
    private String realTeacherId() {
        UserDto teacher = userService.create(new CreateUserRequest(
                "proposal_supervisor_" + OpaqueIds.generate("u"), "InitialPass123!", "Supervising Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        return teacher.id();
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
                "student", "zh-CN"), "usr_test_admin");
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
