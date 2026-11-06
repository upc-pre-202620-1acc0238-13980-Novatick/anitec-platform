package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.*;
import com.anitec.backend.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
public class PasswordRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final UUID DUMMY_ID = new UUID(0, 0);
    private final AccountRepository accounts;
    private final PasswordRecoveryRepository recoveries;
    private final PasswordEncoderPort passwords;
    private final PasswordRecoveryVerifier verifier;
    private final PasswordRecoveryCodeSource codes;
    private final PasswordRecoveryWorkQueue queue;
    private final SecurityEmailDelivery email;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PasswordRecoveryService(AccountRepository accounts, PasswordRecoveryRepository recoveries,
            PasswordEncoderPort passwords, PasswordRecoveryVerifier verifier, PasswordRecoveryCodeSource codes,
            PasswordRecoveryWorkQueue queue, SecurityEmailDelivery email, Clock clock, PlatformTransactionManager manager) {
        this.accounts=accounts; this.recoveries=recoveries; this.passwords=passwords; this.verifier=verifier;
        this.codes=codes; this.queue=queue; this.email=email; this.clock=clock;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void requireAvailable() { verifier.requireAvailable(); }
    public void request(String rawEmail) {
        request(rawEmail, EmailLanguage.ES);
    }
    public void request(String rawEmail, EmailLanguage language) {
        requireAvailable();
        String normalized=Account.normalizeEmail(rawEmail);
        EmailLanguage selected=language == null ? EmailLanguage.ES : language;
        try { queue.submit(() -> process(normalized, selected)); }
        catch (RejectedExecutionException full) { throw DomainException.unavailable("RECOVERY_UNAVAILABLE", "La recuperación no está disponible"); }
    }
    private void process(String normalized, EmailLanguage language) {
        try {
            tx.executeWithoutResult(status -> {
                var found=accounts.findByEmailForUpdate(normalized);
                // Every well-formed request is queued; account lookup/delivery is never on the public response path.
                if (found.isEmpty() || !found.get().canSignIn()) {
                    verifier.sign(DUMMY_ID,DUMMY_ID,"000000"); return;
                }
                Account account=found.get(); var now=clock.instant();
                var previous=recoveries.find(account.getId()).orElse(null);
                if (previous!=null && !previous.allowsEmission(now)) return;
                UUID id=UUID.randomUUID(); String code=null;
                for (int attempt=0; attempt<8; attempt++) {
                    String candidate=codes.generate();
                    if (candidate==null || !candidate.matches("[0-9]{6}"))
                        throw new IllegalStateException("Invalid recovery code source");
                    if (previous==null || previous.verifier()==null ||
                            !MessageDigest.isEqual(previous.verifier(),verifier.sign(account.getId(),previous.emissionId(),candidate))) {
                        code=candidate; break;
                    }
                }
                if (code==null) throw new IllegalStateException("Recovery code generation unavailable");
                var issued=PasswordRecovery.issue(account.getId(),previous,id,verifier.sign(account.getId(),id,code),now);
                recoveries.save(issued);
                email.passwordRecovery(account.getId(),id,account.getEmail(),code,issued.expiresAt(),language);
            });
        } catch (Exception failure) {
            // Provider/database exceptions may contain sensitive payloads: never log them or their messages.
            log.warn("Password recovery processing could not be completed");
        }
    }
    public void confirm(String rawEmail, String code, String newPassword) {
        requireAvailable();
        Account.requirePasswordCompliance(newPassword); // Before looking at account/code; does not reveal validity.
        if (code==null || !code.matches("[0-9]{6}")) throw invalid();
        String normalized=Account.normalizeEmail(rawEmail);
        // Same password work for usable, unknown and invalid-code requests, never a login.
        String newHash=passwords.encode(newPassword);
        boolean success;
        try {
        success=Boolean.TRUE.equals(tx.execute(status -> {
            var found=accounts.findByEmailForUpdate(normalized);
            if (found.isEmpty() || !found.get().canSignIn()) {
                verifier.sign(DUMMY_ID,DUMMY_ID,code); return false;
            }
            Account account=found.get(); var now=clock.instant();
            var recovery=recoveries.find(account.getId()).orElse(null);
            if (recovery==null) { verifier.sign(account.getId(),DUMMY_ID,code); return false; }
            if (!recovery.usable(now)) {
                recoveries.save(recovery.invalidate(now)); return false;
            }
            if (!MessageDigest.isEqual(recovery.verifier(),verifier.sign(account.getId(),recovery.emissionId(),code))) {
                recoveries.save(recovery.failed(now)); return false;
            }
            recoveries.save(recovery.consume(now));
            account.resetPasswordHash(newHash,now);
            // saveAndFlush fires V15 global revocation in this SAME transaction; no duplicate revocation call.
            accounts.save(account);
            return true;
        }));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // SQL constraint errors may contain row/verifier values: do not pass their payload to public logging.
            throw DomainException.unavailable("RECOVERY_UNAVAILABLE", "La recuperación no está disponible");
        }
        if (!success) throw invalid(); // Failed-attempt state is already committed.
    }
    private static DomainException invalid() {
        return DomainException.badRequest("RECOVERY_CODE_INVALID", "No se pudo completar la recuperación");
    }
}
