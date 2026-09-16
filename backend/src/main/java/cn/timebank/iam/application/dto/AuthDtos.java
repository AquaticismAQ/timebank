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
     * First-time activation (10-StudentIdentityAndOnboarding.md §2.4/§3.5).
     * No username: the code is its own lookup key, so there is nothing else to
     * send — and nothing here that identifies the student either.
     *
     * <p>{@code toString()} is overridden because a record's generated one
     * prints every component, and both components of this one are secrets. A
     * single {@code log.debug("...{}", request)} anywhere downstream would
     * otherwise put a live activation code and a child's chosen password into
     * the server log.</p>
     *
     * <p>{@code code} is length-bounded rather than left free: it is
     * canonicalised to ten characters before use, and accepting an unbounded
     * string on a public endpoint only gives an attacker a bigger free
     * request body. The bound is generous enough for the printed grouping
     * plus stray whitespace.</p>
     */
    public record ActivateAccountRequest(@NotBlank @Size(max = 64) String code,
                                          @NotBlank @Size(min = 8, max = 128) String newPassword) {

        @Override
        public String toString() {
            return "ActivateAccountRequest[code=<redacted>, newPassword=<redacted>]";
        }
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
