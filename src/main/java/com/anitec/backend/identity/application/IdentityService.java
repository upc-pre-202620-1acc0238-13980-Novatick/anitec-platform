package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.identity.domain.EmailSender;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Identity command service (report class IdentityService). Coordinates the
 * Account aggregate, e-mail side effects and token issuance. Registration
 * publishes AccountRegistered so Subscriptions creates the free plan in the
 * same transaction (section 11 of the spec).
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final AccountRepository accounts;
    private final PasswordEncoderPort passwordEncoder;
    private final EmailSender emailSender;
    private final DomainEventDispatcher events;
    private final String unavailableCredentialHash;
    private final SessionService sessions;

    public IdentityService(AccountRepository accounts,
                           PasswordEncoderPort passwordEncoder,
                           EmailSender emailSender,
                           DomainEventDispatcher events, SessionService sessions) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.events = events;
        this.sessions = sessions;
        this.unavailableCredentialHash = passwordEncoder.encode("unavailable-verification-context");
    }

    // ----------------------------------------------------------- commands

    public record RegisterCommand(String name, String lastName, String email, String password, Role role) {
    }

    public record RegisterResult(UUID accountId, String email, String emailDelivery) {
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
    }

    public record LoginResult(UUID accountId, String email, Role role,
                              String accessToken, String refreshToken, long expiresIn) {
    }

    public record ProfileUpdate(String name, String lastName, String photoUrl,
                                String address, String dni, String phone) {
    }

    @Transactional
    public RegisterResult register(RegisterCommand command) {
        if (command.role() == null) {
            throw DomainException.badRequest("VALIDATION_ERROR", "role: solo GANADERO o VETERINARIO pueden registrarse");
        }
        Account.requirePasswordCompliance(command.password());

        String email = Account.normalizeEmail(command.email());
        if (accounts.existsByEmail(email)) {
            throw DomainException.conflict("EMAIL_ALREADY_REGISTERED", "Ya existe una cuenta con ese correo");
        }

        String hash = passwordEncoder.encode(command.password());
        Account account = Account.create(email, hash, command.name().trim(), command.lastName().trim(),
                command.role(), Instant.now());

        try {
            accounts.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw DomainException.conflict("EMAIL_ALREADY_REGISTERED", "Ya existe una cuenta con ese correo");
        }

        boolean sent = sendVerificationEmail(account);
        events.publish(new Account.AccountRegistered(account.getId(), account.getEmail(), account.getRole()));
        return new RegisterResult(account.getId(), account.getEmail(), sent ? "SENT" : "UNCONFIRMED");
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void verifyEmail(String email, String code) {
        Account account = accounts.findByEmailForUpdate(Account.normalizeEmail(email))
                .orElseThrow(() -> DomainException.business("INVALID_CODE", "Código inválido o cuenta no encontrada"));
        try {
            account.verifyEmail(code, Instant.now());
        } catch (DomainException failure) {
            // Commit the failed-attempt state before returning the business error.
            // Infrastructure failures still roll back normally.
            accounts.save(account);
            throw failure;
        }
        accounts.save(account);
        events.publish(new Account.EmailVerified(account.getId()));
    }

    public record EmailDelivery(String email, String emailDelivery, int remainingResends, Instant nextResendAt) {
    }

    /** Proof of credentials for this operation only: never grants an application session. */
    @Transactional(readOnly = true)
    public boolean hasVerificationContext(String email, String password) {
        if (password == null || password.isBlank()) return false;
        var found = accounts.findByEmail(Account.normalizeEmail(email));
        try {
            boolean matches = passwordEncoder.matches(password,
                    found.map(Account::getPasswordHash).orElse(unavailableCredentialHash));
            return found.isPresent() && matches;
        } catch (IllegalArgumentException invalidCredential) {
            return false;
        }
    }

    @Transactional
    public EmailDelivery resendVerificationCode(String email) {
        var found = accounts.findByEmailForUpdate(Account.normalizeEmail(email));
        if (found.isEmpty()) {
            // Do not reveal whether the account exists (US26 does not cover it).
            throw DomainException.conflict("VERIFICATION_NOT_PENDING", "No hay una verificación pendiente disponible");
        }
        Account account = found.get();
        if (account.getStatus() != Account.Status.PENDIENTE) {
            throw DomainException.conflict("VERIFICATION_NOT_PENDING", "No hay una verificación pendiente disponible");
        }
        account.issueVerificationCode(Instant.now());
        accounts.save(account);
        boolean sent = sendVerificationEmail(account);
        return new EmailDelivery(account.getEmail(), sent ? "SENT" : "UNCONFIRMED",
                Account.MAX_CODE_RESENDS - account.getVerificationResends(), account.nextVerificationResendAt(Instant.now()));
    }

    @Transactional
    public LoginResult login(String email, String password) {
        Account account = accounts.findByEmailForUpdate(Account.normalizeEmail(email))
                .orElseThrow(() -> DomainException.unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos"));
        if (!passwordEncoder.matches(password, account.getPasswordHash()))
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos");
        if (account.getStatus() == Account.Status.SUSPENDIDA)
            throw DomainException.unauthorized("ACCOUNT_SUSPENDED", "La cuenta está suspendida");
        if (!account.canSignIn())
            throw DomainException.unauthorized("EMAIL_NOT_VERIFIED", "Debe verificar su correo electrónico antes de iniciar sesión");
        TokenPair pair = sessions.create(account);
        return new LoginResult(account.getId(), account.getEmail(), account.getRole(), pair.accessToken(), pair.refreshToken(), pair.expiresIn());
    }

    public TokenPair refresh(String rawRefreshToken, UUID operationId) { return sessions.refresh(rawRefreshToken, operationId); }
    public void logout(String rawRefreshToken) { sessions.logout(rawRefreshToken); }

    @Transactional
    public Account updateProfile(UUID accountId, ProfileUpdate update) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> DomainException.notFound("Cuenta no encontrada"));
        account.updateProfile(update.name(), update.lastName(), update.photoUrl(),
                update.address(), update.dni(), update.phone(), Instant.now());
        return accounts.save(account);
    }

    // -------------------------------------------------------------- queries

    public Account requireAccount(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> DomainException.notFound("Cuenta no encontrada"));
    }

    // ------------------------------------------------------------- helpers

    private boolean sendVerificationEmail(Account account) {
        String body = "Hola " + account.getName() + ",\n\n"
                + "Tu código de verificación de ANITEC es: " + account.getVerificationCode() + "\n"
                + "Vence en 10 minutos. Si no solicitaste este correo, ignóralo.";
        try {
            return emailSender.send(account.getEmail(), "ANITEC - Código de verificación", body);
        } catch (Exception ex) {
            log.warn("Verification e-mail delivery could not be confirmed");
            return false;
        }
    }
}
