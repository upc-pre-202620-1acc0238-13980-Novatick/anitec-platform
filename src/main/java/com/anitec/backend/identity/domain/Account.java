package com.anitec.backend.identity.domain;

import com.anitec.backend.shared.domain.DomainEvent;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import lombok.Getter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account aggregate root (Identity and Access bounded context, report
 * aggregate "Cuenta"). Owns registration state, email verification code
 * lifecycle and profile data. The server always assigns owner, role and
 * timestamps (spec section 6.1).
 */
@Getter
public class Account {

    public enum Status {PENDIENTE, ACTIVA, SUSPENDIDA}

    /** Published when a new account is created (drives free subscription creation). */
    public record AccountRegistered(UUID accountId, String email, Role role) implements DomainEvent {
    }

    /** Published when the e-mail is verified. */
    public record EmailVerified(UUID accountId) implements DomainEvent {
    }

    // Verification policy (requirements.md + interview decision #7)
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final int MAX_CODE_ATTEMPTS = 5;
    public static final int MAX_CODE_RESENDS = 5;
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private static final Pattern PASSWORD_POLICY = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s]).{8,}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private UUID id;
    private String email;
    private String passwordHash;
    private String name;
    private String lastName;
    private String photoUrl;
    private String address;
    private String dni;
    private String phone;
    private Role role;
    private Status status;
    private String verificationCode;
    private Instant verificationExpiresAt;
    private int verificationAttempts;
    private int verificationResends;
    private Instant verificationSentAt;
    private Instant verifiedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected Account() {
    }

    // ------------------------------------------------------------- factories

    /** Validates the raw password policy (requirements.md) before hashing. */
    public static void requirePasswordCompliance(String rawPassword) {
        if (rawPassword == null || !PASSWORD_POLICY.matcher(rawPassword).matches()) {
            throw DomainException.badRequest("VALIDATION_ERROR",
                    "password: debe tener mínimo 8 caracteres con una mayúscula, una minúscula, un número y un carácter especial");
        }
    }

    public static Account create(String email, String passwordHash, String name, String lastName,
                                 Role role, Instant now) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.email = normalizeEmail(email);
        account.passwordHash = passwordHash;
        account.name = name;
        account.lastName = lastName;
        account.role = role;
        account.status = Status.PENDIENTE;
        account.createdAt = now;
        account.updatedAt = now;
        account.verificationAttempts = 0;
        account.verificationResends = 0;
        issueCode(account, now);
        return account;
    }

    /** Reconstitution factory used by the persistence adapter. */
    public static Account restore(UUID id, String email, String passwordHash, String name, String lastName,
                                  String photoUrl, String address, String dni, String phone, Role role,
                                  Status status, String verificationCode, Instant verificationExpiresAt,
                                  int verificationAttempts, int verificationResends, Instant verificationSentAt,
                                  Instant verifiedAt, Instant createdAt, Instant updatedAt) {
        Account account = new Account();
        account.id = id;
        account.email = email;
        account.passwordHash = passwordHash;
        account.name = name;
        account.lastName = lastName;
        account.photoUrl = photoUrl;
        account.address = address;
        account.dni = dni;
        account.phone = phone;
        account.role = role;
        account.status = status;
        account.verificationCode = verificationCode;
        account.verificationExpiresAt = verificationExpiresAt;
        account.verificationAttempts = verificationAttempts;
        account.verificationResends = verificationResends;
        account.verificationSentAt = verificationSentAt;
        account.verifiedAt = verifiedAt;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        return account;
    }

    // ----------------------------------------------------------- operations

    /** Generates (or replaces) the 6 digit verification code applying the policy. */
    public String issueVerificationCode(Instant now) {
        if (verificationCode != null) {
            if (verificationSentAt != null && now.isBefore(verificationSentAt.plus(RESEND_COOLDOWN))) {
                throw DomainException.tooManyRequests("RESEND_COOLDOWN",
                        "Espere 60 segundos antes de solicitar otro código de verificación");
            }
            if (verificationResends >= MAX_CODE_RESENDS) {
                throw DomainException.tooManyRequests("RESEND_COOLDOWN",
                        "Se alcanzó el límite de reenvíos de código; vuelva a intentarlo más tarde");
            }
            verificationResends++;
        }
        verificationCode = randomCode();
        verificationExpiresAt = now.plus(CODE_TTL);
        verificationAttempts = 0;
        verificationSentAt = now;
        updatedAt = now;
        return verificationCode;
    }

    /** Verifies the e-mail (US26). Sending a code never equals verifying it. */
    public void verifyEmail(String code, Instant now) {
        if (verificationCode == null) {
            throw DomainException.business("INVALID_CODE",
                    "No hay un código de verificación vigente; solicite uno nuevo");
        }
        if (now.isAfter(verificationExpiresAt)) {
            throw DomainException.business("CODE_EXPIRED", "El código de verificación expiró; solicite uno nuevo");
        }
        if (verificationAttempts >= MAX_CODE_ATTEMPTS) {
            throw DomainException.business("CODE_ATTEMPTS_EXCEEDED",
                    "Se agotaron los intentos de verificación; solicite un código nuevo");
        }
        boolean matches = MessageDigest.isEqual(
                code == null ? new byte[0] : code.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                verificationCode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!matches) {
            verificationAttempts++;
            if (verificationAttempts >= MAX_CODE_ATTEMPTS) {
                verificationCode = null;
                updatedAt = now;
                throw DomainException.business("CODE_ATTEMPTS_EXCEEDED",
                        "Se agotaron los intentos de verificación; solicite un código nuevo");
            }
            updatedAt = now;
            throw DomainException.business("INVALID_CODE", "El código de verificación es incorrecto");
        }
        status = Status.ACTIVA;
        verifiedAt = now;
        verificationCode = null;
        verificationExpiresAt = null;
        verificationAttempts = 0;
        verificationResends = 0;
        updatedAt = now;
    }

    public boolean canSignIn() {
        return status == Status.ACTIVA && verifiedAt != null;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void updateProfile(String name, String lastName, String photoUrl, String address,
                              String dni, String phone, Instant now) {
        this.name = name;
        this.lastName = lastName;
        this.photoUrl = photoUrl;
        this.address = address;
        this.dni = dni;
        this.phone = phone;
        this.updatedAt = now;
    }

    public void suspend(Instant now) {
        if (role == Role.ADMIN) {
            throw DomainException.forbidden("No se puede suspender una cuenta de administrador");
        }
        this.status = Status.SUSPENDIDA;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        this.status = Status.ACTIVA;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------- helpers

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static void issueCode(Account account, Instant now) {
        account.verificationCode = randomCode();
        account.verificationExpiresAt = now.plus(CODE_TTL);
        account.verificationSentAt = now;
    }

    private static String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public String fullName() {
        return name + " " + lastName;
    }
}
