package cn.timebank.servicerecord.infrastructure;

import cn.timebank.servicerecord.domain.ServiceRecord;
import cn.timebank.servicerecord.domain.ServiceRecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ServiceRecordRepository extends JpaRepository<ServiceRecord, String>, JpaSpecificationExecutor<ServiceRecord> {

    /**
     * {@code recordId -> studentName} for one project, for the project timeline
     * (issue 158). A projection rather than whole entities: the timeline needs
     * two columns, and {@code studentName} is already denormalized onto the row,
     * so this costs one query and no name lookups.
     */
    @Query("select r.id, r.studentName from ServiceRecord r where r.projectId = :projectId")
    List<Object[]> findIdAndStudentNameByProjectId(@Param("projectId") String projectId);

    /**
     * B3.3 duplicate detection: the student's other records — excluding
     * drafts (not yet committed) and rejections (already resolved as
     * invalid) — whose time window overlaps this one.
     */
    @Query("""
            select r from ServiceRecord r
            where r.studentId = :studentId and r.id <> :excludeId
            and r.status in :consideredStatuses
            and r.startAt < :endAt and r.endAt > :startAt
            """)
    List<ServiceRecord> findOverlapping(@Param("studentId") String studentId, @Param("excludeId") String excludeId,
                                         @Param("startAt") Instant startAt, @Param("endAt") Instant endAt,
                                         @Param("consideredStatuses") List<ServiceRecordStatus> consideredStatuses);

    /** B3.11's annual progress read — bounded to one student, one academic year; not "full history". */
    @Query("""
            select coalesce(sum(r.durationMinutes), 0) from ServiceRecord r
            where r.studentId = :studentId and r.serviceDate between :yearStart and :yearEnd and r.status = :status
            """)
    int sumDurationMinutes(@Param("studentId") String studentId, @Param("yearStart") LocalDate yearStart,
                            @Param("yearEnd") LocalDate yearEnd, @Param("status") ServiceRecordStatus status);
}
