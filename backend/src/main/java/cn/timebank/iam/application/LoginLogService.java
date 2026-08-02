package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.LoginLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Contract §4.15's `GET /login-logs`, behind `securityLogs.read`. Append-only (B1.10). */
public interface LoginLogService {

    void record(String userId, String usernameAttempted, boolean success, String failureReason,
                String ipAddress, String userAgent);

    Page<LoginLogDto> list(Pageable pageable);
}
