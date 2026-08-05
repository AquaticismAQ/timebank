package cn.timebank.enrollment;

import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4.7's exit criterion: N concurrent registrations for M places, real
 * threads against real MySQL row locking (`ProjectRepository
 * .findWithLockById`, mirroring `AccountConcurrencyIT`'s proof from B3),
 * yield exactly M confirmations and the rest waitlisted — never M+1.
 */
class EnrollmentCapacityIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private EnrollmentService enrollmentService;

    @Test
    void concurrentRegistrationsNeverOversubscribeCapacity() throws Exception {
        int capacity = 3;
        int applicants = 10;

        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        CreateProjectRequest request = new CreateProjectRequest(
                "Capacity Test Project", "summary", "description", "cat_campus", null,
                start, start.plusSeconds(7200), "Hall", capacity, 100, null, null,
                Instant.now().minusSeconds(60), start);
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        // Force the lazy time-sync to REGISTRATION_OPEN before firing concurrent registrations.
        projectService.getDetail(published.id(), adminAuth(), "zh-CN");

        List<Authentication> studentAuths = IntStream.range(0, applicants)
                .mapToObj(i -> studentAuth(createStudent(i).id()))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(applicants);
        try {
            List<Callable<EnrollmentDto>> tasks = studentAuths.stream()
                    .<Callable<EnrollmentDto>>map(auth -> () -> enrollmentService.register(
                            published.id(), new RegisterRequest(null), auth, "zh-CN"))
                    .toList();
            List<Future<EnrollmentDto>> futures = executor.invokeAll(tasks);
            List<EnrollmentDto> results = new java.util.ArrayList<>();
            for (Future<EnrollmentDto> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            long confirmed = results.stream().filter(r -> r.status().equals("CONFIRMED")).count();
            long waitlisted = results.stream().filter(r -> r.status().equals("WAITLISTED")).count();
            assertThat(confirmed).isEqualTo(capacity);
            assertThat(waitlisted).isEqualTo(applicants - capacity);
        } finally {
            executor.shutdown();
        }
    }

    /** I1: `GET /applications?filter[status]=...` must actually narrow the result set, not silently discard the param. */
    @Test
    void statusFilterNarrowsResultsToMatchingEnrollments() {
        int capacity = 1;

        Instant start = Instant.now().plus(6, ChronoUnit.DAYS);
        CreateProjectRequest request = new CreateProjectRequest(
                "Status Filter Test Project", "summary", "description", "cat_campus", null,
                start, start.plusSeconds(7200), "Hall", capacity, 100, null, null,
                Instant.now().minusSeconds(60), start);
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        // Force the lazy time-sync to REGISTRATION_OPEN before registering.
        projectService.getDetail(published.id(), adminAuth(), "zh-CN");

        Authentication firstStudent = studentAuth(createStudent(200).id());
        Authentication secondStudent = studentAuth(createStudent(201).id());

        // First registration takes the only seat (CONFIRMED); the second, over capacity, is WAITLISTED.
        EnrollmentDto confirmedEnrollment = enrollmentService.register(published.id(), new RegisterRequest(null), firstStudent, "zh-CN");
        EnrollmentDto waitlistedEnrollment = enrollmentService.register(published.id(), new RegisterRequest(null), secondStudent, "zh-CN");
        assertThat(confirmedEnrollment.status()).isEqualTo("CONFIRMED");
        assertThat(waitlistedEnrollment.status()).isEqualTo("WAITLISTED");

        Page<EnrollmentDto> confirmedOnly = enrollmentService.list(PageRequest.of(0, 100), "CONFIRMED", null, adminAuth(), "zh-CN");
        assertThat(confirmedOnly.getContent()).extracting(EnrollmentDto::id).contains(confirmedEnrollment.id());
        assertThat(confirmedOnly.getContent()).extracting(EnrollmentDto::id).doesNotContain(waitlistedEnrollment.id());

        Page<EnrollmentDto> waitlistedOnly = enrollmentService.list(PageRequest.of(0, 100), "WAITLISTED", null, adminAuth(), "zh-CN");
        assertThat(waitlistedOnly.getContent()).extracting(EnrollmentDto::id).contains(waitlistedEnrollment.id());
        assertThat(waitlistedOnly.getContent()).extracting(EnrollmentDto::id).doesNotContain(confirmedEnrollment.id());
    }

    /**
     * I2 hardening: an admin managing one project's registrants needs
     * `filter[projectId]` to actually narrow the result set — added when a
     * browser-verification pass found no admin UI could show a single
     * project's registrant list at all without it (00-Status.md §25).
     */
    @Test
    void projectIdFilterNarrowsResultsToOneProjectsEnrollments() {
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS);
        CreateProjectRequest requestA = new CreateProjectRequest(
                "Project Filter Test A", "summary", "description", "cat_campus", null,
                start, start.plusSeconds(7200), "Hall", 5, 100, null, null,
                Instant.now().minusSeconds(60), start);
        CreateProjectRequest requestB = new CreateProjectRequest(
                "Project Filter Test B", "summary", "description", "cat_campus", null,
                start, start.plusSeconds(7200), "Hall", 5, 100, null, null,
                Instant.now().minusSeconds(60), start);
        ProjectDto draftA = projectService.createDraft(requestA, "usr_test_admin", "zh-CN");
        ProjectDto draftB = projectService.createDraft(requestB, "usr_test_admin", "zh-CN");
        ProjectDto projectA = projectService.publish(draftA.id(), draftA.version(), "usr_test_admin", "zh-CN");
        ProjectDto projectB = projectService.publish(draftB.id(), draftB.version(), "usr_test_admin", "zh-CN");
        projectService.getDetail(projectA.id(), adminAuth(), "zh-CN");
        projectService.getDetail(projectB.id(), adminAuth(), "zh-CN");

        Authentication studentA = studentAuth(createStudent(210).id());
        Authentication studentB = studentAuth(createStudent(211).id());
        EnrollmentDto enrollmentA = enrollmentService.register(projectA.id(), new RegisterRequest(null), studentA, "zh-CN");
        EnrollmentDto enrollmentB = enrollmentService.register(projectB.id(), new RegisterRequest(null), studentB, "zh-CN");

        Page<EnrollmentDto> projectAOnly = enrollmentService.list(PageRequest.of(0, 100), null, projectA.id(), adminAuth(), "zh-CN");
        assertThat(projectAOnly.getContent()).extracting(EnrollmentDto::id).contains(enrollmentA.id());
        assertThat(projectAOnly.getContent()).extracting(EnrollmentDto::id).doesNotContain(enrollmentB.id());
    }

    private StudentDetailDto createStudent(int n) {
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_capacity_" + n, "Capacity Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_" + studentId.substring(4), "usr_" + studentId.substring(4), "hash", "Test Student",
                true, false, "zh-CN", Set.of("student"), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
