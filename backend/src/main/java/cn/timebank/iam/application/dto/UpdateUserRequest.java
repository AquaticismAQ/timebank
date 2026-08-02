package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** `PATCH /users/{id}` — status/role changes, never a password field (contract §13.4 general rule). */
public record UpdateUserRequest(
        @Size(max = 100) String displayName,
        @Email String email,
        String status,
        Set<String> roleCodes,
        @NotNull Long version) {
}
