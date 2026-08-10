package cn.timebank.student.application.dto;

/**
 * {@code GET /students/directory} row (contract §4.9, added alongside the
 * proposal co-organizer picker, `Docs/clarification/01-i3-partner-school-terminology.md`).
 * Deliberately narrower than {@link StudentListItemDto} even though none of
 * that DTO's fields are contract-sensitive (§9 already excludes email/
 * guardian data from it) — this endpoint is reachable by any student via
 * {@code directory.read}, not just staff via {@code students.read}, so it
 * carries only what a co-organizer picker actually renders.
 */
public record StudentDirectoryEntryDto(
        String id,
        String displayName,
        int gradeLevel,
        String className) {
}
