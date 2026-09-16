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

    // There are deliberately no forgot-password or reset-password request types
    // here any more, because there are no such endpoints
    // (Design/*/08-mlps-level1-security-design-guide.md §8.1/§8.2, issue 089).
    // The system has no self-service password recovery: a forgotten password is
    // recovered offline, through an administrator who identifies the person and
    // issues an activation slip. A `ResetPasswordRequest(token, newPassword)`
    // shape only exists to serve an unauthenticated endpoint that mints a
    // credential for an account it was told about, which is the thing §8.2
    // promises schools does not exist here.
    //
    // There is deliberately no admin-reset *response* type here any more
    // (10-StudentIdentityAndOnboarding.md §3.7). The admin reset used to
    // generate a temporary password server-side and hand the plaintext back in
    // this DTO, which falsified §2.4's "no password is ever generated
    // server-side, so no password plaintext exists at any point" — a claim
    // `Design/*/08-mlps-level1-security-design-guide.md` §8.2 repeats to
    // schools (issue 088). A reset now issues an activation code and streams
    // the same one-slip credential document §3.4 prints, so the response is a
    // PDF and there is no password in it, or anywhere else, to model.
}
