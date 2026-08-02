package cn.timebank.iam.application.dto;

import java.time.Instant;
import java.util.Set;

public record RoleDto(String id, String code, String name, int sessionTimeoutMinutes,
                       Set<String> permissionCodes, Long version, Instant createdAt, Instant updatedAt) {
}
