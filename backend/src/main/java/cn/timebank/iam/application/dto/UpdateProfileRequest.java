package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** `PATCH /me/profile` — self-service fields only (contract §2.2). */
public record UpdateProfileRequest(
        @Size(max = 100) String displayName,
        @Email String email,
        String preferredLanguage) {
}
