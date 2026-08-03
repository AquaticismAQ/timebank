package cn.timebank.progress.application.dto;

/**
 * `GET /students/{id}/progress`. Field names match the frontend's
 * `StudentProgress` type (`frontend/src/app/mock/data.ts`) where they
 * overlap — the `*Hours` convenience fields it also carries are
 * frontend-derived and leave the wire (D10.4 point 5); everything here is
 * integer minutes/centi-coins.
 *
 * {@code completedMinutes}/{@code underReviewMinutes}/{@code gapMinutes}
 * are annual (current-academic-year) figures, live-computed from
 * `servicerecord` — bounded to one student/one year, not "full history".
 * {@code timeCoinBalance} is the lifetime ledger balance from `account`.
 * {@code recognitionLevel} reflects the *lifetime* total against the rule
 * in effect when it was last recomputed (see {@code StudentProgress}).
 */
public record ProgressDto(String studentId, int annualRequiredMinutes, int stageRequiredMinutes,
                           int completedMinutes, int underReviewMinutes, int gapMinutes,
                           int timeCoinBalance, String recognitionLevel) {
}
