package cn.timebank.lifecycle.application.dto;

import java.util.List;

/** B6.3/B6.4 — what one purge run (dry or real) found/did, per student and in aggregate. */
public record PurgeRunSummaryDto(String runId, boolean dryRun, String policyVersion,
                                  int studentsScanned, int studentsPurged, int rowsDeleted, int objectsDeleted,
                                  List<StudentPurgeDetail> perStudent) {

    public record StudentPurgeDetail(String studentId, int serviceRecords, int accountTransactions, int cases,
                                      int enrollments, int proposals, int notifications, int evidenceFiles) {
    }
}
