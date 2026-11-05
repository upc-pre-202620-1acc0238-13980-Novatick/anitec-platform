package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.SessionRepository;
import com.anitec.backend.shared.domain.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcSessionRepository implements SessionRepository {
 private final JdbcTemplate jdbc;
 public JdbcSessionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 private static Timestamp ts(Instant i) { return Timestamp.from(i); }
 private static Instant instant(ResultSet rs,String name) throws SQLException {
   Timestamp t=rs.getTimestamp(name);return t==null?null:t.toInstant();
 }
 public Optional<UUID> accountForToken(String digest) {
   return jdbc.query("SELECT s.account_id FROM identity_session_tokens t JOIN identity_sessions s ON s.id=t.session_id WHERE t.token_digest=?",
     (rs,n)->rs.getObject(1,UUID.class),digest).stream().findFirst();
 }
 public void lockAccount(UUID id) { jdbc.queryForObject("SELECT id FROM identity_accounts WHERE id=? FOR UPDATE",UUID.class,id); }
 public Session lockSession(UUID id) {
   return jdbc.queryForObject("SELECT * FROM identity_sessions WHERE id=? FOR UPDATE",(rs,n)->new Session(rs.getObject("id",UUID.class),
     rs.getObject("account_id",UUID.class),instant(rs,"expires_at"),instant(rs,"revoked_at")),id);
 }
 public Optional<Token> token(String digest) {
   return jdbc.query("SELECT * FROM identity_session_tokens WHERE token_digest=?",(rs,n)->new Token(rs.getString("token_digest"),
     rs.getObject("session_id",UUID.class),instant(rs,"expires_at"),instant(rs,"consumed_at"),rs.getObject("operation_id",UUID.class),
     rs.getString("successor_digest"),rs.getBytes("recovery_ciphertext"),instant(rs,"recovery_until")),digest).stream().findFirst();
 }
 public void createSession(Session s,Instant now) { jdbc.update("INSERT INTO identity_sessions(id,account_id,created_at,expires_at) VALUES (?,?,?,?)",s.id(),s.accountId(),ts(now),ts(s.expiresAt())); }
 public void createToken(String digest,UUID id,Instant expires,Instant now) { jdbc.update("INSERT INTO identity_session_tokens(token_digest,session_id,expires_at,created_at) VALUES (?,?,?,?)",digest,id,ts(expires),ts(now)); }
 public boolean operationUsed(UUID sessionId,UUID operationId) {
   return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_session_tokens WHERE session_id=? AND operation_id=?)",Boolean.class,sessionId,operationId));
 }
 public void consume(String digest,UUID op,String successor,byte[] recovery,Instant now) {
   int changed=jdbc.update("UPDATE identity_session_tokens SET consumed_at=?,operation_id=?,successor_digest=?,recovery_ciphertext=? WHERE token_digest=? AND consumed_at IS NULL",ts(now),op,successor,recovery,digest);
   if(changed!=1) throw new IllegalStateException("Refresh consumption conflict");
 }
 public void confirmRecovery(String digest,Instant until) { int changed=jdbc.update("UPDATE identity_session_tokens SET recovery_until=? WHERE token_digest=? AND recovery_ciphertext IS NOT NULL AND recovery_until IS NULL",ts(until),digest); if(changed!=1) throw new IllegalStateException("Session recovery confirmation unavailable"); }
 public void revoke(UUID id,Instant now) {
   jdbc.update("UPDATE identity_sessions SET revoked_at=coalesce(revoked_at,?) WHERE id=?",ts(now),id);
   jdbc.update("UPDATE identity_session_tokens SET recovery_ciphertext=NULL,recovery_until=NULL WHERE session_id=?",id);
 }
 public void revokeAll(UUID id,Instant now) {
   jdbc.update("UPDATE identity_sessions SET revoked_at=coalesce(revoked_at,?) WHERE account_id=?",ts(now),id);
   jdbc.update("UPDATE identity_session_tokens SET recovery_ciphertext=NULL,recovery_until=NULL WHERE session_id IN (SELECT id FROM identity_sessions WHERE account_id=?)",id);
 }
 public boolean authorized(UUID sid,UUID accountId,Role role,Instant now) {
   return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_sessions s JOIN identity_accounts a ON a.id=s.account_id WHERE s.id=? AND s.account_id=? AND s.revoked_at IS NULL AND s.expires_at>? AND a.status='ACTIVA' AND a.verified_at IS NOT NULL AND a.role=?)",Boolean.class,sid,accountId,ts(now),role.name()));
 }
 public void clearRecovery(String digest) { jdbc.update("UPDATE identity_session_tokens SET recovery_ciphertext=NULL,recovery_until=NULL WHERE token_digest=?",digest); }
 public int cleanRecovery(Instant now) { return jdbc.update("UPDATE identity_session_tokens SET recovery_ciphertext=NULL,recovery_until=NULL WHERE recovery_ciphertext IS NOT NULL AND (recovery_until<=? OR (recovery_until IS NULL AND consumed_at<=?) OR session_id IN (SELECT id FROM identity_sessions WHERE expires_at<=? OR revoked_at IS NOT NULL))",ts(now),ts(now.minusSeconds(60)),ts(now)); }
 public int cleanExpiredHistory(Instant now) {
   // Retain the entire chain until seven days AFTER absolute session expiry.
   return jdbc.update("DELETE FROM identity_sessions WHERE expires_at<?",ts(now.minusSeconds(7*86400)));
 }
}
