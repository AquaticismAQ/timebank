package cn.timebank.progress.application.dto;

/**
 * `GET /students/{id}/progress`. Field names match the frontend's
 * `StudentProgress` type (`frontend/src/app/mock/data.ts`) where they
 * overlap — the `*Hours` convenience fields it also carries are
 * frontend-derived and leave the wire (D10.4 point 5); everything here is
 * integer minutes/centi-coins.
 *
 * <p><b>Two different periods live in this record, and mixing them is the
 * defect issue 104 fixes.</b> Anything named for the year is the year;
 * anything named for the stage is lifetime.</p>
 *
 * {@code completedMinutes}/{@code underReviewMinutes}/{@code gapMinutes}
 * are annual (current-academic-year) figures, live-computed from
 * `servicerecord` — bounded to one student/one year, not "full history".
 * {@code stageCompletedMinutes} is the lifetime counterpart, read from
 * `student_progress`'s stored projection, and is the figure to compare
 * against {@code stageRequiredMinutes}: dividing the *annual*
 * {@code completedMinutes} by that cumulative threshold is what showed a
 * student past 300 hours a bar reading "8.5 / 300".
 * {@code timeCoinBalance} is the lifetime ledger balance from `account`.
 * {@code recognitionLevel} reflects the *lifetime* total against the rule
 * in effect when it was last recomputed (see {@code StudentProgress}) — it
 * is authoritative, and per contract §268 the client renders it rather than
 * re-deriving a tier of its own.
 */
public record ProgressDto(String studentId, int annualRequiredMinutes, int stageRequiredMinutes,
                           int completedMinutes, int underReviewMinutes, int gapMinutes,
                           int stageCompletedMinutes, int timeCoinBalance, String recognitionLevel) {
}
