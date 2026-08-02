package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The small request/response shapes for `/auth/*` and `/me/change-password` (contract §2.2). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                         @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    public record ForgotPasswordRequest(@NotBlank String username) {
    }

    public record ResetPasswordRequest(@NotBlank String token,
                                        @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    /**
     * No mail/notification module exists until B5 — the admin-triggered
     * reset (contract §4.15) therefore returns the generated temporary
     * password once, in this response, for the admin to relay out of band.
     * Revisit once B5's notification module can email a reset link instead.
     * {@code username} is included so the caller can revoke live sessions
     * without a second lookup (see {@code UserServiceImpl}'s module-boundary note).
     */
    public record AdminResetPasswordResponse(String username, String temporaryPassword) {
    }
}
