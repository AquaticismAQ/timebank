package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Set;

public final class RoleRequests {

    private RoleRequests() {
    }

    public record CreateRoleRequest(@NotBlank String code, @NotBlank String name,
                                     @Positive int sessionTimeoutMinutes,
                                     @NotEmpty Set<String> permissionCodes) {
    }

    public record UpdateRoleRequest(String name, Integer sessionTimeoutMinutes,
                                     Set<String> permissionCodes, @NotNull Long version) {
    }

    /** 07-RoleFinetune.md §2.7: no code/sessionTimeoutMinutes — see {@code RoleTemplateDto}. */
    public record CreateRoleTemplateRequest(@NotBlank String name, @NotEmpty Set<String> permissionCodes) {
    }

    /** Full-replace on edit (both fields always required) — simpler than {@link UpdateRoleRequest}'s partial-patch shape for a two-field resource. */
    public record UpdateRoleTemplateRequest(@NotBlank String name, @NotEmpty Set<String> permissionCodes,
                                             @NotNull Long version) {
    }
}
