package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.AnnualRolloverReportDto;

/** Design/*&#47;14-AnnualRollover.md — promotion and graduation, once per academic year. */
public interface AnnualRolloverService {

    /**
     * Rolls the school over into the academic year that has begun, if it has
     * not been rolled over already.
     *
     * @param triggeredBy actor id, or {@code "scheduler"} for the automatic run
     * @return what happened, including the "already done" case, which is a
     *         normal outcome rather than an error
     */
    AnnualRolloverReportDto runIfDue(String triggeredBy);
}
