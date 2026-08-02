package cn.timebank.iam.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.dto.LoginLogDto;
import cn.timebank.iam.domain.LoginLog;
import cn.timebank.iam.infrastructure.LoginLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class LoginLogServiceImpl implements LoginLogService {

    private final LoginLogRepository repository;

    LoginLogServiceImpl(LoginLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(String userId, String usernameAttempted, boolean success, String failureReason,
                        String ipAddress, String userAgent) {
        repository.save(new LoginLog(OpaqueIds.generate("llog"), userId, usernameAttempted, success,
                failureReason, ipAddress, truncate(userAgent, 256), Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LoginLogDto> list(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable)
                .map(l -> new LoginLogDto(l.getId(), l.getUserId(), l.getUsernameAttempted(), l.isSuccess(),
                        l.getFailureReason(), l.getIpAddress(), l.getCreatedAt()));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
