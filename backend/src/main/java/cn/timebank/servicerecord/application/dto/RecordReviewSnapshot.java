package cn.timebank.servicerecord.application.dto;

import java.time.LocalDate;

/**
 * What {@code review} needs to run the approval transaction (B3.12)
 * without depending on `servicerecord.domain`/`infrastructure` — a DTO
 * crossing the module boundary, same pattern as {@code EffectiveRule}.
 */
public record RecordReviewSnapshot(String id, String studentId, String gradeId, String classId,
                                    LocalDate serviceDate, int durationMinutes, Long version, String status) {
}
