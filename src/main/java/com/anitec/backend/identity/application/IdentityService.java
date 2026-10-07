package com.anitec.backend.identity.application;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.identity.domain.EmailSender;
import com.anitec.backend.identity.domain.RefreshToken;
import com.anitec.backend.identity.domain.RefreshTokenRepository;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
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
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoderPort passwordEncoder;
    private final EmailSender emailSender;
    private final JwtService jwtService;
    private final DomainEventDispatcher events;

    public IdentityService(AccountRepository accounts,
                           RefreshTokenRepository refreshTokens,
                           PasswordEncoderPort passwordEncoder,
                           EmailSender emailSender,
                           JwtService jwtService,
                           DomainEventDispatcher events) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.jwtService = jwtService;
        this.events = events;
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
        if (command.role() == null || command.role() == Role.ADMIN) {
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

    @Transactional
    public void verifyEmail(String email, String code) {
        Account account = accounts.findByEmail(Account.normalizeEmail(email))
                .orElseThrow(() -> DomainException.business("INVALID_CODE", "Código inválido o cuenta no encontrada"));
        account.verifyEmail(code, Instant.now());
        accounts.save(account);
        events.publish(new Account.EmailVerified(account.getId()));
    }

    public record EmailDelivery(String email, String emailDelivery) {
    }

    @Transactional
    public EmailDelivery resendVerificationCode(String email) {
        var found = accounts.findByEmail(Account.normalizeEmail(email));
        if (found.isEmpty()) {
            // Do not reveal whether the account exists (US26 does not cover it).
            return new EmailDelivery(email, "UNCONFIRMED");
        }
        Account account = found.get();
        if (account.getStatus() == Account.Status.ACTIVA) {
            return new EmailDelivery(account.getEmail(), "UNCONFIRMED");
        }
        account.issueVerificationCode(Instant.now());
        accounts.save(account);
        boolean sent = sendVerificationEmail(account);
        return new EmailDelivery(account.getEmail(), sent ? "SENT" : "UNCONFIRMED");
    }

    @Transactional
    public LoginResult login(String email, String password) {
        Account account = accounts.findByEmail(Account.normalizeEmail(email))
                .orElseThrow(() -> DomainException.unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos"));
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos");
        }
        if (account.getStatus() == Account.Status.SUSPENDIDA) {
            throw DomainException.unauthorized("ACCOUNT_SUSPENDED", "La cuenta está suspendida; contacte al administrador");
        }
        if (!account.canSignIn()) {
            throw DomainException.unauthorized("EMAIL_NOT_VERIFIED", "Debe verificar su correo electrónico antes de iniciar sesión");
        }
        TokenPair pair = issueTokens(account);
        return new LoginResult(account.getId(), account.getEmail(), account.getRole(),
                pair.accessToken(), pair.refreshToken(), pair.expiresIn());
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokens.findByDigest(RefreshToken.digest(rawRefreshToken))
                .orElseThrow(() -> DomainException.unauthorized("INVALID_CREDENTIALS", "Refresh token inválido"));
        if (!token.isValidAt(Instant.now())) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Sesión expirada; inicie sesión nuevamente");
        }
        Account account = accounts.findById(token.getAccountId())
                .orElseThrow(() -> DomainException.unauthorized("INVALID_CREDENTIALS", "Sesión inválida"));
        if (!account.canSignIn()) {
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "La cuenta no tiene acceso activo");
        }
        token.revoke(Instant.now());           // rotation: old token is invalidated
        refreshTokens.save(token);
        return issueTokens(account);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByDigest(RefreshToken.digest(rawRefreshToken)).ifPresent(token -> {
            token.revoke(Instant.now());
            refreshTokens.save(token);
        });
    }

    @Transactional
    public Account updateProfile(UUID accountId, ProfileUpdate update) {
        Account account = requireAccount(accountId);
        account.updateProfile(update.name(), update.lastName(), update.photoUrl(),
                update.address(), update.dni(), update.phone(), Instant.now());
        return accounts.save(account);
    }

    /** Public API used by the Admin context (context map section 5.2). */
    @Transactional
    public Account changeUserStatus(UUID accountId, Account.Status status) {
        Account account = requireAccount(accountId);
        if (status == Account.Status.SUSPENDIDA) {
            account.suspend(Instant.now());
        } else {
            account.activate(Instant.now());
        }
        return accounts.save(account);
    }

    // -------------------------------------------------------------- queries

    public Account requireAccount(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> DomainException.notFound("Cuenta no encontrada"));
    }

    // ------------------------------------------------------------- helpers

    private TokenPair issueTokens(Account account) {
        Instant now = Instant.now();
        String access = jwtService.createAccessToken(account.getId(), account.getEmail(), account.getRole());
        String rawRefresh = RefreshToken.newRawToken();
        refreshTokens.save(RefreshToken.issue(account.getId(), rawRefresh, now, jwtService.refreshExpirationMs()));
        return new TokenPair(access, rawRefresh, jwtService.accessTokenTtlSeconds());
    }

    private boolean sendVerificationEmail(Account account) {
        String body = "Hola " + account.getName() + ",\n\n"
                + "Tu código de verificación de ANITEC es: " + account.getVerificationCode() + "\n"
                + "Vence en 10 minutos. Si no solicitaste este correo, ignóralo.";
        try {
            return emailSender.send(account.getEmail(), "ANITEC - Código de verificación", body);
        } catch (Exception ex) {
            log.warn("Verification e-mail could not be sent to {}: {}", account.getEmail(), ex.getMessage());
            return false;
        }
    }
}
