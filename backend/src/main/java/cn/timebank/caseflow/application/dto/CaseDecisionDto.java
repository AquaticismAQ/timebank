package cn.timebank.caseflow.application.dto;

import java.time.Instant;

public record CaseDecisionDto(String id, String caseId, String reviewerId, String decision, String reasonCode,
                               String comment, long caseVersionAtDecision, Instant createdAt) {
}
