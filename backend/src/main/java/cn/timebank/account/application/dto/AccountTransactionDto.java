package cn.timebank.account.application.dto;

import java.time.Instant;

public record AccountTransactionDto(String id, String accountId, String type, int amount, int balanceAfter,
                                     String sourceType, String sourceId, String reasonCode, String memo,
                                     String operatorId, String reversalOfTransactionId, Instant createdAt) {
}
