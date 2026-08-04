package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto;

import java.util.List;

/**
 * B6.3/B6.4/B6.5 — Design.md §16.3. Cohort-scoped (by {@code
 * graduation_year}), never row-age-scoped: getting this wrong destroys a
 * currently-enrolled student's early-grade records, and is unrecoverable
 * (R5). Dry-run reports exactly what a real run would delete, without
 * deleting anything.
 */
public interface CohortPurgeService {

    /** One {@link PurgeRunSummaryDto} per eligible graduation-year cohort found — empty if none are eligible yet. */
    List<PurgeRunSummaryDto> run(boolean dryRun, String actorId);
}
