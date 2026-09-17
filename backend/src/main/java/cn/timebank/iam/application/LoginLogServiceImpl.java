package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.dto.LoginLogDto;
import cn.timebank.iam.domain.LoginLog;
import cn.timebank.iam.infrastructure.LoginLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class LoginLogServiceImpl implements LoginLogService {

    private final LoginLogRepository repository;
    private final AuditService auditService;

    LoginLogServiceImpl(LoginLogRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void record(String userId, String usernameAttempted, boolean success, String failureReason,
                        String ipAddress, String userAgent) {
        repository.save(new LoginLog(OpaqueIds.generate("llog"), userId, usernameAttempted, success,
                failureReason, ipAddress, truncate(userAgent, 256), Instant.now()));

        // Authentication is a security event, and §10 of the MLPS design guide
        // commits to the audit trail covering them — but until now a sign-in
        // reached `login_log` only, so the audit log an administrator actually
        // reads after an incident contained no logins at all.
        //
        // Written here rather than at AuthServiceImpl's four call sites (one
        // success, three refusals) so that a fifth can never be added that
        // records a login without auditing it. The password is not a parameter
        // of this method and cannot leak into the row; the failure reason is an
        // enum-like code (LOCKED/INACTIVE/BAD_CREDENTIALS), never the input.
        auditService.record(new AuditEntry(
                userId, null, success ? "user.login" : "user.loginFailed", "user", userId,
                null, auditSummary(usernameAttempted, ipAddress, failureReason),
                success ? "SUCCESS" : failureReason));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LoginLogDto> list(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable)
                .map(l -> new LoginLogDto(l.getId(), l.getUserId(), l.getUsernameAttempted(), l.isSuccess(),
                        l.getFailureReason(), l.getIpAddress(), l.getCreatedAt()));
    }

    /**
     * `actor_id` is null for a failed attempt on an unknown username — there is
     * no account to attribute it to — which is why the attempted username is
     * carried here instead. Both columns are nullable (V1 baseline).
     *
     * <p>Returns a {@code Map}, not a hand-built JSON string: {@code AuditService}
     * puts the summary through Jackson, so a pre-serialised string comes back
     * quoted and escaped as a JSON <em>string</em> rather than an object, unlike
     * every other audit row. Caught by reading the rows this actually wrote.</p>
     */
    private Map<String, Object> auditSummary(String usernameAttempted, String ipAddress, String failureReason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("usernameAttempted", usernameAttempted);
        if (ipAddress != null) {
            summary.put("ip", ipAddress);
        }
        if (failureReason != null) {
            summary.put("failureReason", failureReason);
        }
        return summary;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
