package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditService;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserListQueryRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.iam.web.UserController;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.security.application.SessionRevocationService;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import cn.timebank.student.application.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue 094. `GET /users` grew a search box, four filters and three sort
 * options; the way a change of that shape goes wrong is that a parameter is
 * accepted and then quietly dropped somewhere between the query string and
 * the SQL, which from the outside is indistinguishable from one that works.
 *
 * <p>So nothing here asserts "the call succeeded". The fake {@link
 * UserListQueryRepository} below holds <b>one</b> fixed set of accounts
 * spanning two roles, two statuses, two grades and two classes, and narrows
 * it using only the {@link UserListFilter} and {@link Pageable} it is
 * <em>actually handed</em> — the same semantics the real SQL applies. Every
 * assertion is therefore about the result set <em>changing</em>: if {@code
 * UserController} stopped reading {@code filter[gradeId]}, or {@code
 * UserServiceImpl} stopped forwarding the filter, the fake would see an empty
 * filter, return everybody, and the exclusion assertions would fail.</p>
 *
 * <p>The SQL itself is not exercised here (there is no database in {@code mvn
 * test}) — {@code UserListQuerySqlTest} covers the generated statement, and
 * the two are kept deliberately parallel: a predicate added to one belongs in
 * the other.</p>
 *
 * <p>Names are NATO alphabet words, not personas, and their insertion order
 * (Delta, Bravo, Alfa, Charlie, Echo, Foxtrot) is neither ascending nor
 * descending — a sort assertion that passed on insertion order would prove
 * nothing.</p>
 */
class UserListFilterSortTest {

    private static final String GRADE_6 = "grd_6";
    private static final String GRADE_8 = "grd_8";
    private static final String CLASS_6A = "cls_6a";
    private static final String CLASS_8C = "cls_8c";

    /** studentId -> placement, exactly what the real query's left join to `student`/`grade`/`class` supplies. */
    private static final Map<String, Placement> ROSTER = Map.of(
            "std_delta", new Placement(GRADE_6, 6, CLASS_6A, "A"),
            "std_bravo", new Placement(GRADE_6, 6, CLASS_8C, "C"),
            "std_alfa", new Placement(GRADE_8, 8, CLASS_8C, "C"),
            "std_charlie", new Placement(GRADE_8, 8, CLASS_6A, "A"));

    private record Placement(String gradeId, int gradeLevel, String classId, String className) {
    }

    /**
     * Four students and two staff accounts. Every dimension the endpoint can
     * filter on takes at least two distinct values, and no two dimensions line
     * up: grade 6 spans both classes, so a grade filter cannot accidentally
     * pass by behaving like a class filter, and vice versa.
     */
    private static final List<UserDto> ACCOUNTS = List.of(
            account("usr_delta", "Delta", "student", "ACTIVE", "std_delta"),
            account("usr_bravo", "Bravo", "student", "INACTIVE", "std_bravo"),
            account("usr_alfa", "Alfa", "student", "ACTIVE", "std_alfa"),
            account("usr_charlie", "Charlie", "student", "INACTIVE", "std_charlie"),
            account("usr_echo", "Echo", "teacher", "ACTIVE", null),
            account("usr_foxtrot", "Foxtrot", "teacher", "INACTIVE", null));

    private static UserDto account(String id, String displayName, String roleCode, String status, String studentId) {
        Placement placement = studentId != null ? ROSTER.get(studentId) : null;
        return new UserDto(id, displayName.toLowerCase(), displayName, displayName.toLowerCase() + "@example.test",
                studentId, studentId == null ? "staff-" + id : null, status, "en", roleCode, 1L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), Set.of(),
                placement == null ? null : placement.gradeId(),
                placement == null ? null : placement.gradeLevel(),
                placement == null ? null : placement.classId(),
                placement == null ? null : placement.className(),
                // lastLoginAt: this fixture is about filtering and ordering, and
                // no assertion here reads it.
                null);
    }

    // ── filters ──────────────────────────────────────────────────────────

    @Test
    void role_filter_excludes_the_other_role() {
        assertThat(names(list(filters(Map.of("role", "student")))))
                .containsExactlyInAnyOrder("Delta", "Bravo", "Alfa", "Charlie")
                .doesNotContain("Echo", "Foxtrot");
        assertThat(names(list(filters(Map.of("role", "teacher")))))
                .containsExactlyInAnyOrder("Echo", "Foxtrot");
    }

    @Test
    void status_filter_excludes_the_other_status() {
        assertThat(names(list(filters(Map.of("status", "ACTIVE")))))
                .containsExactlyInAnyOrder("Delta", "Alfa", "Echo")
                .doesNotContain("Bravo", "Charlie", "Foxtrot");
        assertThat(names(list(filters(Map.of("status", "INACTIVE")))))
                .containsExactlyInAnyOrder("Bravo", "Charlie", "Foxtrot");
    }

    @Test
    void grade_filter_excludes_the_other_grade_and_every_staff_account() {
        assertThat(names(list(filters(Map.of("gradeId", GRADE_6)))))
                .containsExactlyInAnyOrder("Delta", "Bravo")
                .doesNotContain("Alfa", "Charlie", "Echo", "Foxtrot");
        assertThat(names(list(filters(Map.of("gradeId", GRADE_8)))))
                .containsExactlyInAnyOrder("Alfa", "Charlie");
    }

    @Test
    void class_filter_excludes_the_other_class() {
        // Class 6A holds one grade-6 and one grade-8 student, so a class filter
        // that silently behaved like a grade filter would fail here.
        assertThat(names(list(filters(Map.of("classId", CLASS_6A)))))
                .containsExactlyInAnyOrder("Delta", "Charlie")
                .doesNotContain("Bravo", "Alfa", "Echo", "Foxtrot");
        assertThat(names(list(filters(Map.of("classId", CLASS_8C)))))
                .containsExactlyInAnyOrder("Bravo", "Alfa");
    }

    @Test
    void search_matches_display_name_and_excludes_everyone_else() {
        assertThat(names(list(new PageQuery(1, 20, List.of(), Map.of(), "alf"))))
                .containsExactly("Alfa");
        assertThat(names(list(new PageQuery(1, 20, List.of(), Map.of(), "ECH"))))
                .containsExactly("Echo");
    }

    @Test
    void filters_combine_rather_than_replace_one_another() {
        assertThat(names(list(filters(Map.of("role", "student", "status", "INACTIVE", "gradeId", GRADE_6)))))
                .containsExactly("Bravo");
    }

    @Test
    void an_unfiltered_list_returns_everybody() {
        // The control: without this, every assertion above could be satisfied by
        // a fake that returned nothing.
        assertThat(names(list(new PageQuery(1, 20, List.of(), Map.of(), null)))).hasSize(6);
    }

    // ── sorting ──────────────────────────────────────────────────────────

    @Test
    void name_sort_reverses_between_ascending_and_descending() {
        List<String> ascending = names(list(sortedBy("displayName", false)));
        List<String> descending = names(list(sortedBy("displayName", true)));
        assertThat(ascending).containsExactly("Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot");
        assertThat(descending).containsExactly("Foxtrot", "Echo", "Delta", "Charlie", "Bravo", "Alfa");
        assertThat(descending).isNotEqualTo(ascending);
    }

    @Test
    void grade_sort_orders_by_grade_level_and_reverses() {
        // Restricted to students so the assertion is about grade order, not about
        // where the ungraded staff accounts land.
        List<String> ascending = names(list(sortedBy("gradeLevel", false, Map.of("role", "student"))));
        List<String> descending = names(list(sortedBy("gradeLevel", true, Map.of("role", "student"))));
        assertThat(ascending).containsExactly("Bravo", "Delta", "Alfa", "Charlie");
        assertThat(descending).containsExactly("Alfa", "Charlie", "Bravo", "Delta");
        assertThat(descending).isNotEqualTo(ascending);
    }

    @Test
    void class_sort_orders_by_class_name_and_reverses() {
        List<String> ascending = names(list(sortedBy("className", false, Map.of("role", "student"))));
        List<String> descending = names(list(sortedBy("className", true, Map.of("role", "student"))));
        assertThat(ascending).containsExactly("Charlie", "Delta", "Alfa", "Bravo");
        assertThat(descending).containsExactly("Alfa", "Bravo", "Charlie", "Delta");
        assertThat(descending).isNotEqualTo(ascending);
    }

    // ── grade vs. scope ──────────────────────────────────────────────────

    @Test
    void a_student_carries_their_own_grade_and_class_and_no_scope() {
        UserDto delta = one(list(filters(Map.of("role", "student"))), "Delta");
        assertThat(delta.gradeId()).isEqualTo(GRADE_6);
        assertThat(delta.gradeLevel()).isEqualTo(6);
        assertThat(delta.classId()).isEqualTo(CLASS_6A);
        assertThat(delta.className()).isEqualTo("A");
        // 07-RoleFinetune.md §2.6.2: a student role takes no grade bond at all.
        assertThat(delta.scopeGradeIds()).isEmpty();
    }

    @Test
    void a_staff_members_scope_is_never_reported_as_their_grade() {
        UserDto echo = one(list(filters(Map.of("role", "teacher"))), "Echo");
        // The teacher IS bonded to two grades — see the stub in `list` — so this
        // would pass vacuously if the scope were empty.
        assertThat(echo.scopeGradeIds()).containsExactlyInAnyOrder(GRADE_6, GRADE_8);
        assertThat(echo.gradeId()).isNull();
        assertThat(echo.gradeLevel()).isNull();
        assertThat(echo.classId()).isNull();
        assertThat(echo.className()).isNull();
    }

    // ── harness ──────────────────────────────────────────────────────────

    private static PageQuery filters(Map<String, String> filters) {
        return new PageQuery(1, 20, List.of(), filters, null);
    }

    private static PageQuery sortedBy(String property, boolean descending) {
        return sortedBy(property, descending, Map.of());
    }

    private static PageQuery sortedBy(String property, boolean descending, Map<String, String> filters) {
        return new PageQuery(1, 20, List.of(new PageQuery.SortToken(property, descending)), filters, null);
    }

    private static List<String> names(List<UserDto> users) {
        return users.stream().map(UserDto::displayName).toList();
    }

    private static UserDto one(List<UserDto> users, String displayName) {
        return users.stream().filter(u -> u.displayName().equals(displayName)).findFirst().orElseThrow();
    }

    /** Drives the real controller and the real service; only the query repository is faked. */
    private List<UserDto> list(PageQuery query) {
        TeacherScopeAssignmentService scopes = mock(TeacherScopeAssignmentService.class);
        when(scopes.gradesOf(anyString())).thenReturn(Set.of(GRADE_6, GRADE_8));

        UserService service = new UserServiceImpl(
                mock(UserRepository.class), new FakeUserListQueryRepository(), mock(RoleRepository.class),
                mock(PasswordEncoder.class), mock(AuditService.class), mock(FeatureFlagService.class),
                mock(StudentService.class), mock(StudentUsernameGenerator.class), 5, 15, 480);
        UserController controller = new UserController(service, mock(SessionRevocationService.class), scopes);

        ApiResponse<List<UserDto>> response = controller.list(query);
        return response.data();
    }

    /**
     * Applies the filter and sort it is handed to {@link #ACCOUNTS}, mirroring
     * the real statement in {@code UserListQueryRepository}: a {@code like} on
     * the lower-cased display name, equality on role/status/grade/class, and
     * SQL null ordering (nulls first ascending, last descending) for the two
     * placement columns that staff accounts don't have.
     */
    private static final class FakeUserListQueryRepository extends UserListQueryRepository {

        FakeUserListQueryRepository() {
            super(null);
        }

        @Override
        public List<UserDto> queryPage(UserListFilter filter, Pageable pageable) {
            List<UserDto> matching = new ArrayList<>(matching(filter));
            matching.sort(comparator(pageable.getSort()));
            int from = (int) Math.min(pageable.getOffset(), matching.size());
            int to = Math.min(from + pageable.getPageSize(), matching.size());
            return List.copyOf(matching.subList(from, to));
        }

        @Override
        public long countMatching(UserListFilter filter) {
            return matching(filter).size();
        }

        private List<UserDto> matching(UserListFilter filter) {
            return ACCOUNTS.stream()
                    .filter(u -> !filter.hasSearch()
                            || u.displayName().toLowerCase().contains(filter.search().toLowerCase()))
                    .filter(u -> !filter.hasRoleCode() || filter.roleCode().equals(u.roleCode()))
                    .filter(u -> !filter.hasStatus() || filter.status().equals(u.status()))
                    .filter(u -> !filter.hasGradeId() || filter.gradeId().equals(u.gradeId()))
                    .filter(u -> !filter.hasClassId() || filter.classId().equals(u.classId()))
                    .toList();
        }

        private Comparator<UserDto> comparator(Sort sort) {
            Comparator<UserDto> comparator = null;
            for (Sort.Order order : sort) {
                Comparator<UserDto> next = switch (order.getProperty()) {
                    case "displayName" -> Comparator.comparing(UserDto::displayName);
                    case "username" -> Comparator.comparing(UserDto::username);
                    case "gradeLevel" -> Comparator.comparing(UserDto::gradeLevel, nullsFirstNatural());
                    case "className" -> Comparator.comparing(UserDto::className, nullsFirstNatural());
                    case "id" -> Comparator.comparing(UserDto::id);
                    default -> throw new IllegalStateException("Unmapped sort property: " + order.getProperty());
                };
                if (order.isDescending()) {
                    next = next.reversed();
                }
                comparator = comparator == null ? next : comparator.thenComparing(next);
            }
            return comparator == null ? Comparator.comparing(UserDto::id) : comparator;
        }

        private static <T extends Comparable<T>> Comparator<T> nullsFirstNatural() {
            return Comparator.nullsFirst(Comparator.naturalOrder());
        }
    }
}
