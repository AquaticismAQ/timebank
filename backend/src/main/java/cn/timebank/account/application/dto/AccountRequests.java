package cn.timebank.account.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class AccountRequests {

    private AccountRequests() {
    }

    /** `POST /accounts/{id}/adjustments` — signed centi-coins (contract §13.4). */
    public record AdjustmentRequest(@NotNull Integer amount, @NotBlank String reasonCode, String memo) {
    }

    /** `POST /account-transactions/{id}/reverse`. */
    public record ReverseRequest(@NotBlank String reasonCode, String memo) {
    }

    public record FreezeRequest(@NotBlank String reasonCode, String memo) {
    }
}
