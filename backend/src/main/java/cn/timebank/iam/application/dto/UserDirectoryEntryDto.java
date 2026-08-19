package cn.timebank.iam.application.dto;

/**
 * {@code GET /users/directory} row (contract §4.15, added alongside the
 * proposal supervisor picker, `Docs/optimization/01-i3-partner-school-terminology.md`).
 * Deliberately minimal — no username/email/studentId/roleCodes/status — this
 * endpoint is reachable by any student via {@code directory.read}, not just
 * staff via {@code users.read}.
 */
public record UserDirectoryEntryDto(
        String id,
        String displayName) {
}
