package cn.timebank.organization.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class OrganizationRequests {

    private OrganizationRequests() {
    }

    public record CreateOrganizationRequest(
            @NotBlank String name, @NotBlank String type, String contactName,
            String contactEmail, String contactPhone, String address) {
    }

    public record UpdateOrganizationRequest(
            String name, String contactName, String contactEmail, String contactPhone,
            String address, @NotNull Long version) {
    }
}
