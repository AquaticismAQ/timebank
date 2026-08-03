package cn.timebank.account.application.dto;

import java.time.Instant;

public record AccountDto(String id, String studentId, int balance, boolean frozen, String frozenReason,
                          Long version, Instant createdAt, Instant updatedAt) {
}
