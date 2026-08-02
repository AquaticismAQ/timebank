package cn.timebank.security.application;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
class SessionRevocationServiceImpl implements SessionRevocationService {

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    SessionRevocationServiceImpl(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void revokeAllSessionsForUser(String username) {
        Map<String, ? extends Session> sessions =
                sessionRepository.findByPrincipalName(username);
        sessions.keySet().forEach(sessionRepository::deleteById);
    }
}
