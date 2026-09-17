package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        // Every account is created ACTIVE, so asking for a status nothing holds
        // is the only query that can tell "filtering" from "ignoring" here —
        // `status=active` returns the whole fixture either way.
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

    private UserDto createUser(String displayName, String roleCode) {
        return userService.create(new CreateUserRequest(
                "lst_" + OpaqueIds.generate("u"), "InitialChoice123!", displayName, null,
                roleCode, "zh-CN"), "usr_test_admin");
    }
}
