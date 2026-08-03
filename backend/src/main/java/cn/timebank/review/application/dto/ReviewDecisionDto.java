package cn.timebank.review.application.dto;

import java.time.Instant;

public record ReviewDecisionDto(String id, String serviceRecordId, String reviewerId, String decision,
                                 String reasonCode, String comment, long recordVersionAtDecision, Instant createdAt) {
}
