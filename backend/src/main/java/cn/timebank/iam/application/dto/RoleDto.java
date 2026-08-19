package cn.timebank.iam.application.dto;

import java.time.Instant;
import java.util.Set;

/**
 * {@code userCount}: how many users currently hold this role — the same
 * number {@code DELETE /roles/{id}} would report in its 409 if the role is
 * in use (07-RoleFinetune.md §2.4). Surfaced here so the Roles tab can show
 * it as a column and disable the Delete action up front, rather than
 * letting an admin open a confirm dialog for a deletion the backend will
 * always reject.
 */
public record RoleDto(String id, String code, String name, int sessionTimeoutMinutes,
                       Set<String> permissionCodes, boolean isProtected, int userCount, Long version,
                       Instant createdAt, Instant updatedAt) {
}
