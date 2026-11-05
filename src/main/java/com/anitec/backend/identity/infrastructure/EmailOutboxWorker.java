package com.anitec.backend.identity.infrastructure;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Each claim/result is a short transaction. No transaction remains open during HTTP. */
@Component
public class EmailOutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(EmailOutboxWorker.class);
    private final JdbcTemplate jdbc;
    private final EmailDeliverySettings settings;
    private final ResendEmailSender sender;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean scheduled;
    private volatile boolean stopping;
    public record Claim(UUID id,UUID emission,String purpose,UUID owner,int attempts,String key,
                        Instant expires,byte[] nonce,byte[] ciphertext) {
        @Override public String toString(){return "EmailClaim["+id+"]";}
    }
    public EmailOutboxWorker(JdbcTemplate jdbc,EmailDeliverySettings settings,ResendEmailSender sender,
                             Clock clock,PlatformTransactionManager manager,Environment env) {
        this.jdbc=jdbc;this.settings=settings;this.sender=sender;this.clock=clock;
        tx=new TransactionTemplate(manager);scheduled=env.getProperty("email.delivery.worker-enabled",Boolean.class,true);
    }
    @Scheduled(fixedDelayString="${email.delivery.poll-ms:1000}")
    public void poll() {
        if(!scheduled || stopping)return;
        if(!settings.enabled){
            try {cleanup();}catch(Exception unavailable){log.warn("Email outbox cleanup unavailable (STORAGE)");}
            return;
        }
        try {for(int n=0;n<settings.batchSize && !stopping;n++)if(!runOne())break;}
        catch(Exception unavailable){log.warn("Email outbox processing unavailable (STORAGE_OR_CRYPTO)");}
    }
    public boolean runOne() {
        if(!settings.enabled || stopping)return false;
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Email HTTP cannot run inside a transaction");
        Claim claim=claim();if(claim==null)return false;
        // Recheck immediately before HTTP; a subsequent commit elsewhere cannot recall an in-flight request.
        boolean eligible=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM identity_email_outbox j WHERE id=? AND state='PROCESSING' AND claim_id=?
            AND claim_until>? AND expires_at>? AND identity_email_eligible(account_id,purpose,emission_id,?))
            """,Boolean.class,claim.id(),claim.owner(),ts(clock.instant()),ts(clock.instant()),ts(clock.instant())));
        if(!eligible){finish(claim,new ResendEmailSender.Result(ResendEmailSender.Kind.FAILED,null,null,"INELIGIBLE"));return true;}
        String payload;
        try {payload=new EmailOutboxCipher(settings.encryptionKey()).decrypt(claim.id(),claim.emission(),claim.purpose(),
                new EmailOutboxCipher.Envelope(claim.nonce(),claim.ciphertext()));}
        catch(Exception invalid){finish(claim,new ResendEmailSender.Result(ResendEmailSender.Kind.FAILED,null,null,"ENVELOPE_INVALID"));return true;}
        var result=sender.deliver(payload,claim.key(),clock.instant());
        finish(claim,result);return true;
    }
    public Claim claim() {
        if(!settings.enabled || stopping)return null;
        return tx.execute(status->{
            Instant now=clock.instant();cleanup(now);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT paused_until>? FROM identity_email_delivery_gate WHERE id=1",Boolean.class,ts(now))))return null;
            UUID owner=UUID.randomUUID();
            var rows=jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM identity_email_outbox WHERE
                    ((state IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?) OR (state='PROCESSING' AND claim_until<=?))
                    AND attempts<? AND expires_at>? AND created_at+interval '24 hours'>?
                    AND identity_email_eligible(account_id,purpose,emission_id,?)
                    ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE identity_email_outbox j SET state='PROCESSING',claim_id=?,claim_until=?,
                    attempts=j.attempts+1,updated_at=? FROM candidate c WHERE j.id=c.id RETURNING j.*
                """,(r,n)->new Claim(r.getObject("id",UUID.class),r.getObject("emission_id",UUID.class),r.getString("purpose"),
                    r.getObject("claim_id",UUID.class),r.getInt("attempts"),r.getString("idempotency_key"),
                    r.getTimestamp("expires_at").toInstant(),r.getBytes("nonce"),r.getBytes("ciphertext")),
                ts(now),ts(now),settings.maxAttempts,ts(now),ts(now),ts(now),owner,ts(now.plusSeconds(settings.leaseSeconds)),ts(now));
            return rows.isEmpty()?null:rows.getFirst();
        });
    }
    /** Fenced update: expired/lost claims cannot acknowledge or schedule another attempt. */
    public boolean finish(Claim claim,ResendEmailSender.Result result) {
        return Boolean.TRUE.equals(tx.execute(status->{
            Instant now=clock.instant();
            var locked=jdbc.queryForList("""
                SELECT identity_email_eligible(account_id,purpose,emission_id,?) AS eligible,expires_at
                FROM identity_email_outbox WHERE id=? AND state='PROCESSING' AND claim_id=? AND claim_until>? FOR UPDATE
                """,ts(now),claim.id(),claim.owner(),ts(now));
            if(locked.isEmpty())return false;
            String state;Instant next=now;boolean eligible=Boolean.TRUE.equals(locked.getFirst().get("eligible"));
            if(!now.isBefore(claim.expires()))state="EXPIRED";
            else if(!eligible)state="CANCELLED";
            else if(result.kind()==ResendEmailSender.Kind.ACCEPTED && result.providerId()!=null)state="ACCEPTED";
            else if(result.kind()==ResendEmailSender.Kind.FAILED)state="FAILED";
            else {
                boolean uncertain=result.kind()==ResendEmailSender.Kind.UNCERTAIN;
                int[] delays={5,15,45,120};
                long seconds=delays[Math.min(claim.attempts()-1,3)];
                Duration delay=Duration.ofMillis(seconds*1000+ThreadLocalRandom.current().nextLong(0,1001));
                if(result.retryAfter()!=null && result.retryAfter().compareTo(delay)>0)delay=result.retryAfter();
                boolean beforeExpiry=delay.compareTo(Duration.between(now,claim.expires()))<0;
                next=beforeExpiry?now.plus(delay):now;
                state=claim.attempts()>=settings.maxAttempts || !beforeExpiry ? (uncertain?"UNKNOWN":"FAILED") : "RETRY_WAIT";
                if(result.kind()==ResendEmailSender.Kind.CONFIGURATION || result.kind()==ResendEmailSender.Kind.QUOTA) {
                    Duration pause=result.retryAfter()==null?Duration.ofMinutes(5):result.retryAfter();
                    if(pause.compareTo(Duration.ofDays(365))>0)
                        jdbc.update("UPDATE identity_email_delivery_gate SET paused_until='infinity',error_category=? WHERE id=1",result.category());
                    else jdbc.update("UPDATE identity_email_delivery_gate SET paused_until=GREATEST(paused_until,?),error_category=? WHERE id=1",ts(now.plus(pause)),result.category());
                }
            }
            boolean terminal=!state.equals("RETRY_WAIT");
            jdbc.update("""
                UPDATE identity_email_outbox SET state=?,next_attempt_at=?,provider_id=?,error_category=?,
                claim_id=NULL,claim_until=NULL,updated_at=?,completed_at=?,
                ciphertext=CASE WHEN ? THEN NULL ELSE ciphertext END,nonce=CASE WHEN ? THEN NULL ELSE nonce END WHERE id=?
                """,state,ts(next),result.providerId(),result.category(),ts(now),terminal?ts(now):null,terminal,terminal,claim.id());
            if(state.equals("FAILED") || result.kind()==ResendEmailSender.Kind.CONFIGURATION || result.kind()==ResendEmailSender.Kind.QUOTA)
                log.warn("Email job {}: {}",claim.id(),result.category());
            return true;
        }));
    }
    public void cleanup() {tx.executeWithoutResult(status->cleanup(clock.instant()));}
    private void cleanup(Instant now) {
        jdbc.update("""
            UPDATE identity_email_outbox SET state=CASE WHEN expires_at<=? THEN 'EXPIRED'
                WHEN NOT identity_email_eligible(account_id,purpose,emission_id,?) THEN 'CANCELLED' ELSE 'UNKNOWN' END,
                ciphertext=NULL,nonce=NULL,claim_id=NULL,claim_until=NULL,completed_at=?,updated_at=?
            WHERE state IN ('PENDING','RETRY_WAIT','PROCESSING') AND
              (expires_at<=? OR NOT identity_email_eligible(account_id,purpose,emission_id,?)
               OR (attempts>=? AND state='PROCESSING' AND claim_until<=?))
            """,ts(now),ts(now),ts(now),ts(now),ts(now),ts(now),settings.maxAttempts,ts(now));
        jdbc.update("""
            DELETE FROM identity_email_outbox WHERE id IN (
                SELECT id FROM identity_email_outbox WHERE completed_at<? ORDER BY completed_at,id
                FOR UPDATE SKIP LOCKED LIMIT 500)
            """,ts(now.minus(Duration.ofDays(14))));
    }
    @PreDestroy public void stop(){stopping=true;}
    private static Timestamp ts(Instant instant){return Timestamp.from(instant);}
}
