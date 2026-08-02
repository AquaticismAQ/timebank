package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String initialPassword,
        @NotBlank @Size(max = 100) String displayName,
        @Email String email,
        @NotEmpty Set<String> roleCodes,
        String preferredLanguage) {
}
