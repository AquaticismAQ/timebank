package cn.timebank.security.application;

/**
 * B1.3: "revocation is immediate" — deactivating a user or resetting their
 * password must kill their live sessions, not merely stop new ones. This
 * is exactly why sessions live in MySQL (D4/D1) rather than a stateless
 * JWT: revocation is a direct row delete, not a blocklist.
 */
public interface SessionRevocationService {

    void revokeAllSessionsForUser(String username);
}
