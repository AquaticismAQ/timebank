package cn.timebank.student.application.dto;

/**
 * `GET /students` row. No email, guardian name, or guardian contact —
 * contract §9: list responses must not return them. {@link StudentDetailDto}
 * is the only shape that may carry them, and only when the caller has
 * `students.manage`.
 *
 * @param completedMinutes      approved service minutes in the current academic
 *                              year, and {@code annualRequiredMinutes} what the
 *                              rule in force asks of this student's stage. Both
 *                              are here so the row carries what the list is
 *                              *about* (issue 159): the page used to fetch
 *                              {@code /students/&#123;id&#125;/progress} once per row to get
 *                              them, twenty requests a page, and then filter on
 *                              them in the browser — which is why a selective
 *                              filter emptied pages the pager still counted.
 * @param annualRequiredMinutes see above. Zero only if no rule is in effect,
 *                              which the progress endpoint treats as a fault
 *                              rather than a default.
 */
public record StudentListItemDto(
        String id,
        String studentNumber,
        String displayName,
        String gradeId,
        int gradeLevel,
        String classId,
        String className,
        String status,
        int graduationYear,
        long completedMinutes,
        int annualRequiredMinutes,
        Long version) {
}
