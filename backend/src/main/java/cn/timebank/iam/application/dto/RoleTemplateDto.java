package cn.timebank.iam.application.dto;

import java.time.Instant;
import java.util.Set;

/**
 * 07-RoleFinetune.md §2.7: a role template's simplified wire shape — no
 * {@code code}/{@code sessionTimeoutMinutes}, unlike {@link RoleDto}, since
 * neither is meaningful for a row that's never assigned to a user.
 */
public record RoleTemplateDto(String id, String name, Set<String> permissionCodes, Long version,
                               Instant createdAt, Instant updatedAt) {
}
