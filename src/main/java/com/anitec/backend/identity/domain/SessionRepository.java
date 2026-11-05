package com.anitec.backend.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.anitec.backend.shared.domain.Role;

/** Session persistence port. Mutations require a transaction; lock order: account, session. */
public interface SessionRepository {
 record Session(UUID id, UUID accountId, Instant expiresAt, Instant revokedAt) {
   public boolean active(Instant now) { return revokedAt == null && now.isBefore(expiresAt); }
 }
 record Token(String digest, UUID sessionId, Instant expiresAt, Instant consumedAt,
              UUID operationId, String successorDigest, byte[] recovery, Instant recoveryUntil) {}
 Optional<UUID> accountForToken(String digest);
 void lockAccount(UUID id);
 Session lockSession(UUID id);
 Optional<Token> token(String digest);
 void createSession(Session session, Instant now);
 void createToken(String digest, UUID sessionId, Instant expiresAt, Instant now);
 boolean operationUsed(UUID sessionId, UUID operationId);
 void consume(String digest, UUID operationId, String successor, byte[] recovery, Instant now);
 void confirmRecovery(String digest, Instant until);
 void revoke(UUID sessionId, Instant now);
 void revokeAll(UUID accountId, Instant now);
 boolean authorized(UUID sessionId, UUID accountId, Role role, Instant now);
 void clearRecovery(String digest);
 int cleanRecovery(Instant now);
 int cleanExpiredHistory(Instant now);
}
