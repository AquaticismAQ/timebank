package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /users/directory} and the {@code proposedSupervisorId}
 * validation it exists to support (`Docs/clarification/01-i3-partner-school-terminology.md`) —
 * a minimal id+displayName search scoped to one role, and an existence+role
 * check for a supervisor id a student picked through it.
 */
class UserDirectoryIT extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    void searchDirectoryFindsActiveUsersWithTheRequestedRoleAndMatchingName() {
        String marker = "Marker" + OpaqueIds.generate("m");
        UserDto teacher = createUser(marker + " Teacher", "teacher");
        createUser(marker + " Student", "student"); // same name marker, wrong role — must not match

        List<UserDirectoryEntryDto> results = userService.searchDirectory("teacher", marker);
        assertThat(results).extracting(UserDirectoryEntryDto::id).containsExactly(teacher.id());
        assertThat(results).extracting(UserDirectoryEntryDto::displayName).containsExactly(marker + " Teacher");
    }

    @Test
    void searchDirectoryReturnsEmptyForBlankRoleOrSearch() {
        String marker = "Marker" + OpaqueIds.generate("m");
        createUser(marker + " Teacher", "teacher");

        assertThat(userService.searchDirectory("teacher", null)).isEmpty();
        assertThat(userService.searchDirectory("teacher", "")).isEmpty();
        assertThat(userService.searchDirectory(null, marker)).isEmpty();
        assertThat(userService.searchDirectory("", marker)).isEmpty();
    }

    @Test
    void searchDirectoryIsCappedAtTwenty() {
        String marker = "Capped" + OpaqueIds.generate("m");
        for (int i = 0; i < 25; i++) {
            createUser(marker + " Teacher " + i, "teacher");
        }

        assertThat(userService.searchDirectory("teacher", marker)).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void hasActiveRoleIsTrueOnlyForARealActiveUserHoldingThatRole() {
        UserDto teacher = createUser("Real Teacher " + OpaqueIds.generate("t"), "teacher");
        UserDto student = createUser("Real Student " + OpaqueIds.generate("s"), "student");

        assertThat(userService.hasActiveRole(teacher.id(), "teacher")).isTrue();
        assertThat(userService.hasActiveRole(student.id(), "teacher")).isFalse();
        assertThat(userService.hasActiveRole("usr_does_not_exist", "teacher")).isFalse();
    }

    private UserDto createUser(String displayName, String roleCode) {
        return userService.create(new CreateUserRequest(
                "dir_" + OpaqueIds.generate("u"), "InitialPass123!", displayName, null,
                Set.of(roleCode), "zh-CN"), "usr_test_admin");
    }
}
