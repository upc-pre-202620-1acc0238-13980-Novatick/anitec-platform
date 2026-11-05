package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.application.SecurityEmailDelivery;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** This adapter MUST join the emission transaction. A staging failure rolls it back. */
@Repository
public class JdbcSecurityEmailOutbox implements SecurityEmailDelivery {
    private final JdbcTemplate jdbc;
    private final EmailDeliverySettings settings;
    private final ObjectMapper json;
    private final Clock clock;
    public JdbcSecurityEmailOutbox(JdbcTemplate jdbc,EmailDeliverySettings settings,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc;this.settings=settings;this.json=json;this.clock=clock;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void verification(Account account) {
        if(!settings.enabled)return;
        try {
        UUID emission=jdbc.queryForObject("SELECT verification_emission_id FROM identity_accounts WHERE id=?",UUID.class,account.getId());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_email_outbox WHERE purpose='EMAIL_VERIFICATION' AND emission_id=?)",Boolean.class,emission)))return;
        stage(account.getId(),emission,"EMAIL_VERIFICATION",account.getEmail(),account.getVerificationCode(),account.getVerificationExpiresAt());
        } catch(RuntimeException unavailable) {throw unavailable();}
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void passwordRecovery(UUID account,UUID emission,String recipient,String code,Instant expires) {
        if(settings.enabled)try {stage(account,emission,"PASSWORD_RECOVERY",recipient,code,expires);}
        catch(RuntimeException unavailable) {throw unavailable();}
    }
    private void stage(UUID account,UUID emission,String purpose,String recipient,String code,Instant expires) {
        UUID id=UUID.randomUUID(); Instant now=clock.instant();
        String payload=SecurityEmailTemplates.payload(json,settings.from,recipient,purpose,code,"es");
        var encrypted=new EmailOutboxCipher(settings.encryptionKey()).encrypt(id,emission,purpose,payload);
        jdbc.update("""
            INSERT INTO identity_email_outbox(id,account_id,purpose,emission_id,state,ciphertext,nonce,next_attempt_at,
                expires_at,idempotency_key,created_at,updated_at)
            VALUES(?,?,?,?,'PENDING',?,?,?,?,?,?,?) ON CONFLICT(purpose,emission_id) DO NOTHING
            """,id,account,purpose,emission,encrypted.ciphertext(),encrypted.nonce(),Timestamp.from(now),
                Timestamp.from(expires),"anitec-email/"+id,Timestamp.from(now),Timestamp.from(now));
    }
    private static DomainException unavailable(){return DomainException.unavailable("EMAIL_UNAVAILABLE","No se pudo preparar el correo de seguridad");}
}
