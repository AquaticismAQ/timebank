package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.infrastructure.PurgeQueryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One student, one transaction, {@code REQUIRES_NEW} — a separate bean so
 * {@link CohortPurgeServiceImpl}'s loop over a cohort reaches this through
 * the Spring proxy (not a self-invocation, the B0/B1 lesson) and so one
 * student's failure rolls back only that student's rows, not the whole
 * cohort already processed before it.
 */
@Component
class PurgeStudentTransaction {

    private final PurgeQueryRepository purgeQueryRepository;

    PurgeStudentTransaction(PurgeQueryRepository purgeQueryRepository) {
        this.purgeQueryRepository = purgeQueryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int deleteStudent(String studentId) {
        return purgeQueryRepository.deleteAllForStudent(studentId);
    }
}
