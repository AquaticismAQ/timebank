package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Issue 094 §7 — the read model behind {@code GET /users}, against real MySQL.
 *
 * <p>{@code UserListQuerySqlTest} asserts the <em>shape</em> of the generated
 * statement: which predicates appear, which parameters bind. It cannot catch a
 * column that does not exist, a join MySQL rejects, or an {@code order by} that
 * parses differently than intended — the statement is never executed there. That
 * gap was closed once by probing the deployed server by hand, which proves
 * nothing about the next edit. This is the standing guard.</p>
 *
 * <p>Every assertion pairs a positive with a negative: a filter that is accepted
 * and silently ignored returns the unfiltered set, which is indistinguishable
 * from a working filter unless something is asserted to be <em>missing</em>.
 * This repository has shipped exactly that bug more than once.</p>
 */
class UserListFilterSortIT extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void theQueryExecutesAndPaginatesAgainstRealMySql() {
        createUser("Exec Probe " + OpaqueIds.generate("u"), "teacher");

        var page = userService.list(PageRequest.of(0, 1), UserListFilter.NONE);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isPositive();
    }

    @Test
    void theRoleFilterExcludesOtherRoles() {
        String marker = "RoleFx" + OpaqueIds.generate("m");
        UserDto teacher = createUser(marker + " Teacher", "teacher");
        UserDto student = createUser(marker + " Student", "student");

        List<String> ids = idsOf(new UserListFilter(marker, "teacher", null, null, null));

        assertThat(ids).contains(teacher.id());
        assertThat(ids)
                .as("a role filter that is accepted and ignored would still return the student")
                .doesNotContain(student.id());
    }

    @Test
    void theStatusFilterExcludesOtherStatuses() {
        String marker = "StatFx" + OpaqueIds.generate("m");
        UserDto active = createUser(marker + " Active", "teacher");

        // The fixture helper activates, so every account here is ACTIVE (see
        // createUser). Asking for a status nothing holds is the only query that
        // can tell "filtering" from "ignoring" — `status=ACTIVE` returns the
        // whole fixture either way.
        assertThat(idsOf(new UserListFilter(marker, null, "INACTIVE", null, null)))
                .as("no fixture account is INACTIVE, so an honest filter returns none")
                .isEmpty();
        assertThat(idsOf(new UserListFilter(marker, null, "ACTIVE", null, null))).contains(active.id());
    }

    @Test
    void searchMatchesTheDisplayNameAndNotOtherAccounts() {
        String mine = "Searchable" + OpaqueIds.generate("m");
        UserDto wanted = createUser(mine + " Person", "teacher");
        UserDto other = createUser("Unrelated " + OpaqueIds.generate("o"), "teacher");

        List<String> ids = idsOf(new UserListFilter(mine, null, null, null, null));

        assertThat(ids).containsExactly(wanted.id());
        assertThat(ids).doesNotContain(other.id());
    }

    @Test
    void sortingByDisplayNameOrdersBothWaysAndTheyDiffer() {
        String marker = "SortFx" + OpaqueIds.generate("m");
        // Inserted in an order that is neither ascending nor descending, so a
        // query that ignores `order by` cannot accidentally satisfy both.
        createUser(marker + " Mike", "teacher");
        createUser(marker + " Alice", "teacher");
        createUser(marker + " Zoe", "teacher");

        List<String> asc = namesOf(marker, Sort.by("displayName").ascending());
        List<String> desc = namesOf(marker, Sort.by("displayName").descending());

        assertThat(asc).containsExactly(marker + " Alice", marker + " Mike", marker + " Zoe");
        assertThat(desc).containsExactly(marker + " Zoe", marker + " Mike", marker + " Alice");
        assertThat(asc).isNotEqualTo(desc);
    }

    @Test
    void sortingByGradeLevelIsAcceptedByMySql() {
        // `gradeLevel` maps to `grade.sequence`, not `grade_id` — a column that
        // exists only through the left joins. If those joins or that alias were
        // wrong, MySQL would reject the statement here rather than in production.
        String marker = "GradeSort" + OpaqueIds.generate("m");
        createUser(marker + " Staff", "teacher");

        assertThat(namesOf(marker, Sort.by("gradeLevel").ascending())).isNotEmpty();
        assertThat(namesOf(marker, Sort.by("className").descending())).isNotEmpty();
    }

    /**
     * Issue 097. A brute-force lockout writes {@code locked_until} and never
     * {@code status}, so before this the {@code LOCKED} filter asked MySQL for
     * rows nothing produces, and a locked-out account was returned — and
     * displayed — as {@code ACTIVE}.
     *
     * <p>Run here rather than only in {@code UserListQuerySqlTest} because the
     * derivation is a {@code case} expression in a hand-written statement:
     * that test proves the two copies of the rule agree as <em>text</em>, and
     * nothing but MySQL can prove the expression parses, that it is legal in
     * both the projection and the {@code where} clause, and that {@code now(3)}
     * compares the way the Java side does.</p>
     */
    @Test
    void aLockedOutAccountIsFoundByTheLockedFilterAndNotByActive() {
        String marker = "LockFx" + OpaqueIds.generate("m");
        UserDto locked = createUser(marker + " Locked Out", "teacher");
        UserDto normal = createUser(marker + " Signed In Fine", "teacher");
        lockOut(locked.id(), Instant.now().plus(15, ChronoUnit.MINUTES));

        assertThat(idsOf(new UserListFilter(marker, null, "LOCKED", null, null)))
                .as("the account whose lockout window is open, and only that one")
                .containsExactly(locked.id());
        assertThat(idsOf(new UserListFilter(marker, null, "ACTIVE", null, null)))
                .as("a locked-out account must not also answer to ACTIVE — it is one or the other")
                .containsExactly(normal.id());
    }

    @Test
    void theRowReportsLockedAndCarriesTheReleaseTime() {
        String marker = "LockRow" + OpaqueIds.generate("m");
        UserDto user = createUser(marker + " Locked Out", "teacher");
        Instant releasesAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        lockOut(user.id(), releasesAt);

        UserDto row = userService.list(PageRequest.of(0, 20),
                new UserListFilter(marker, null, null, null, null)).getContent().get(0);

        assertThat(row.status()).isEqualTo("LOCKED");
        assertThat(row.lockedUntil()).isCloseTo(releasesAt, within(1, ChronoUnit.SECONDS));
        // The stored column is untouched — that is the whole point of deriving.
        assertThat(userRepository.findById(user.id()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * The reason for deriving rather than persisting: nothing runs at expiry.
     * A stored {@code LOCKED} would still read {@code LOCKED} here.
     */
    @Test
    void anExpiredLockoutReportsActiveAgainWithNothingHavingRun() {
        String marker = "LockExp" + OpaqueIds.generate("m");
        UserDto user = createUser(marker + " Lock Expired", "teacher");
        lockOut(user.id(), Instant.now().minus(1, ChronoUnit.SECONDS));

        UserDto row = userService.list(PageRequest.of(0, 20),
                new UserListFilter(marker, null, null, null, null)).getContent().get(0);

        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.lockedUntil()).isNull();
        assertThat(idsOf(new UserListFilter(marker, null, "LOCKED", null, null))).isEmpty();
    }

    /** {@code totalItems} must describe the filtered set, so the count query needs the same derivation. */
    @Test
    void theCountQueryDerivesTheStatusToo() {
        String marker = "LockCnt" + OpaqueIds.generate("m");
        createUser(marker + " One", "teacher");
        UserDto locked = createUser(marker + " Two", "teacher");
        lockOut(locked.id(), Instant.now().plus(15, ChronoUnit.MINUTES));

        assertThat(userService.list(PageRequest.of(0, 20),
                new UserListFilter(marker, null, "LOCKED", null, null)).getTotalElements())
                .as("a page of 1 with a total of 2 is the bug this guards")
                .isEqualTo(1);
    }

    /** Writes `locked_until` the way `recordFailedLogin` does, without 5 real login attempts. */
    private void lockOut(String userId, Instant until) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setLockedUntil(until);
        userRepository.saveAndFlush(user);
    }

    @Test
    void aStaffAccountHasNoStudentPlacement() {
        String marker = "Placement" + OpaqueIds.generate("m");
        createUser(marker + " Teacher", "teacher");

        UserDto staff = userService.list(PageRequest.of(0, 20),
                new UserListFilter(marker, null, null, null, null)).getContent().get(0);

        assertThat(staff.gradeId()).isNull();
        assertThat(staff.gradeLevel()).isNull();
        assertThat(staff.classId()).isNull();
        assertThat(staff.className()).isNull();
    }

    private List<String> idsOf(UserListFilter filter) {
        return userService.list(PageRequest.of(0, 50), filter).getContent().stream().map(UserDto::id).toList();
    }

    private List<String> namesOf(String marker, Sort sort) {
        return userService.list(PageRequest.of(0, 50, sort),
                        new UserListFilter(marker, null, null, null, null))
                .getContent().stream().map(UserDto::displayName).toList();
    }

    /**
     * Creates an account and puts it in the state a real one reaches after
     * activation — {@code ACTIVE}, with a password hash.
     *
     * <p>The activation step is not incidental. Since issue 096 every account
     * is born {@code PENDING_ACTIVATION}, so a fixture that skipped it would
     * make {@link #theStatusFilterExcludesOtherStatuses()} assert {@code ACTIVE}
     * against a page of pending accounts, and would make the lockout tests
     * below compare {@code LOCKED} against {@code PENDING_ACTIVATION} rather
     * than against the {@code ACTIVE} the derivation actually has to override.
     * Both would fail for a reason having nothing to do with what they check.</p>
     *
     * <p>Written directly rather than through {@code ActivationService} on
     * purpose: this class is about the list read model, and a redemption per
     * fixture row would spend the per-IP attempt budget and couple these tests
     * to the activation flow's own rules. {@code AuthFlowIT} owns that path.</p>
     */
    private UserDto createUser(String displayName, String roleCode) {
        UserDto created = userService.create(new CreateUserRequest(
                "lst_" + OpaqueIds.generate("u"), displayName, null,
                roleCode, "zh-CN"), "usr_test_admin");
        User user = userRepository.findById(created.id()).orElseThrow();
        user.setPasswordHash("$2a$10$fixture.hash.not.a.real.credential.....................");
        user.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        return created;
    }
}
