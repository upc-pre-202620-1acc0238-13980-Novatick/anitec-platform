package com.anitec.backend.identity.application;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.UUID;
@Service
public class SessionService {
 private final SessionRepository sessions;
 private final AccountRepository accounts;
 private final JwtService jwt;
 private final SessionRecoveryCipher crypto;
 private final Clock clock;
 private final TransactionTemplate tx;
 public SessionService(SessionRepository sessions,AccountRepository accounts,JwtService jwt,SessionRecoveryCipher crypto,Clock clock,PlatformTransactionManager manager) {
   this.sessions=sessions;this.accounts=accounts;this.jwt=jwt;this.crypto=crypto;this.clock=clock;
   tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 @Transactional
 public IdentityService.TokenPair create(Account account) {
   sessions.lockAccount(account.getId());
   Instant now=clock.instant();
   var session=new SessionRepository.Session(UUID.randomUUID(),account.getId(),now.plus(Duration.ofDays(7)),null);
   sessions.createSession(session,now);
   return issue(session,account,now);
 }
 private IdentityService.TokenPair issue(SessionRepository.Session session,Account account,Instant now) {
   String raw=RefreshToken.newRawToken();
   Instant expires=min(session.expiresAt(),now.plusMillis(jwt.refreshExpirationMs()));
   sessions.createToken(RefreshToken.digest(raw),session.id(),expires,now);
   String access=jwt.createAccessToken(account.getId(),account.getEmail(),account.getRole(),session.id(),now,session.expiresAt());
   return new IdentityService.TokenPair(access,raw,Math.max(0,Duration.between(now,min(session.expiresAt(),now.plusSeconds(jwt.accessTokenTtlSeconds()))).getSeconds()));
 }
 private static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
 private static DomainException invalid() { return DomainException.unauthorized("SESSION_INVALID","Sesión inválida; inicie sesión nuevamente"); }
 private record Outcome(IdentityService.TokenPair pair,DomainException error,boolean created) {}
 public IdentityService.TokenPair refresh(String raw,UUID operation) {
   if(operation==null || operation.version()!=4 || operation.variant()!=2) throw DomainException.badRequest("VALIDATION_ERROR","operationId es obligatorio");
   String digest=RefreshToken.digest(raw);
   Outcome outcome=null;
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   do {
   outcome=tx.execute(status->{
     var owner=sessions.accountForToken(digest);if(owner.isEmpty()) return new Outcome(null,invalid(),false);
     try { sessions.lockAccount(owner.get()); }
     catch(org.springframework.dao.EmptyResultDataAccessException missing) { return new Outcome(null,invalid(),false); }
     var token=sessions.token(digest).orElse(null);
     if(token==null) return new Outcome(null,invalid(),false);
     SessionRepository.Session session;
     try { session=sessions.lockSession(token.sessionId()); }
     catch(org.springframework.dao.EmptyResultDataAccessException missing) { return new Outcome(null,invalid(),false); }
     Instant now=clock.instant();
     var account=accounts.findById(owner.get()).orElseThrow();
     if(!session.active(now)||!account.canSignIn()) { sessions.revoke(session.id(),now);return new Outcome(null,invalid(),false); }
     // Reload after acquiring the session lock: another transaction may have consumed it.
     token=sessions.token(digest).orElse(null);
     if(token==null) return new Outcome(null,invalid(),false);
     if(token.consumedAt()!=null) {
       var successor=sessions.token(token.successorDigest()).orElseThrow();
       if(operation.equals(token.operationId()) && token.recoveryUntil()!=null && now.isBefore(token.recoveryUntil())
           && token.recovery()!=null && successor.consumedAt()==null && now.isBefore(successor.expiresAt())) {
         return new Outcome(crypto.decrypt(token.recovery(),context(session.id(),digest,operation)),null,false);
       }
       if(operation.equals(token.operationId()) && token.recoveryUntil()==null && token.recovery()!=null
           && successor.consumedAt()==null && now.isBefore(successor.expiresAt())) return new Outcome(null,null,false);
       // A crash between durable rotation and recovery confirmation fails closed.
       sessions.revoke(session.id(),now);return new Outcome(null,invalid(),false);
     }
     if(!now.isBefore(token.expiresAt())) { sessions.revoke(session.id(),now);return new Outcome(null,invalid(),false); }
     if(sessions.operationUsed(session.id(),operation)) { sessions.revoke(session.id(),now);return new Outcome(null,invalid(),false); }
     var pair=issue(session,account,now);
     sessions.consume(digest,operation,RefreshToken.digest(pair.refreshToken()),crypto.encrypt(pair,context(session.id(),digest,operation)),now);
     return new Outcome(pair,null,true);
   });
   if(outcome.pair()!=null || outcome.error()!=null) break;
   if(System.nanoTime()>=deadline) throw DomainException.unavailable(
       "REFRESH_CONFIRMATION_PENDING","Renovacion pendiente de confirmacion; reintente la misma operacion");
   java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
   } while(true);
   if(outcome.error()!=null) throw outcome.error(); // Revocation is committed before the 401.
   if(outcome.created()) {
     // Rotation is now committed. Confirmation is separate so a rollback can never publish a recoverable result.
     // The initial response is not released until this marker has committed.
     tx.executeWithoutResult(status->{
       var owner=sessions.accountForToken(digest).orElseThrow();sessions.lockAccount(owner);
       var token=sessions.token(digest).orElseThrow();var session=sessions.lockSession(token.sessionId());
       if(session.active(clock.instant())) sessions.confirmRecovery(digest,clock.instant().plusSeconds(60));
     });
   }
   return outcome.pair();
 }
 public void logout(String raw) {
   String digest=RefreshToken.digest(raw);
   tx.executeWithoutResult(status->{
     var owner=sessions.accountForToken(digest);if(owner.isEmpty()) return;
     try {
       sessions.lockAccount(owner.get());var token=sessions.token(digest).orElse(null);
       if(token==null) return;
       var session=sessions.lockSession(token.sessionId());sessions.revoke(session.id(),clock.instant());
     } catch(org.springframework.dao.EmptyResultDataAccessException alreadyRemoved) {
       // Expired history may have been purged after credential lookup: logout remains idempotent.
     }
   });
 }
 @Transactional
 public void revokeAll(UUID accountId) { sessions.lockAccount(accountId);sessions.revokeAll(accountId,clock.instant()); }
 public boolean authorized(UUID sessionId,UUID accountId,com.anitec.backend.shared.domain.Role role) {
   return sessions.authorized(sessionId,accountId,role,clock.instant());
 }
 @Scheduled(cron="${identity.sessions.cleanup-cron:*/30 * * * * *}")
 public void cleanRecovery() { sessions.cleanRecovery(clock.instant()); sessions.cleanExpiredHistory(clock.instant()); }
 private static String context(UUID sessionId,String digest,UUID operation) { return sessionId+":"+digest+":"+operation; }
}
