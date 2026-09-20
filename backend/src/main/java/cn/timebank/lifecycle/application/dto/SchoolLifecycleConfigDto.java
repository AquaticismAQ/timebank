package cn.timebank.lifecycle.application.dto;

import java.util.List;

/**
 * The two facts about a school's year that are configured rather than stored,
 * published so a client does not have to hardcode a second copy of them.
 *
 * @param exitGradeLevels   the grade levels that end a stage — finishing one
 *                          graduates the student rather than promoting them
 *                          (`Design/&#42;/13-AnnualRollover.md` §2.1). Ascending.
 * @param academicYearStart the day the school year turns over, and therefore the
 *                          day promotion and graduation run, as {@code MM-DD}.
 *                          Read-only here: it comes from the deployment's
 *                          environment, not the database, so there is nothing to
 *                          PATCH — see {@code Docs/operations/DeploymentEnv.md}.
 */
public record SchoolLifecycleConfigDto(List<Integer> exitGradeLevels, String academicYearStart) {
}
