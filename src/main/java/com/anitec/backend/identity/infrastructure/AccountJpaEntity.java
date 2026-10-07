package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persistence model for {@code identity_accounts} (Flyway V1). Mapping between
 * this entity and the domain aggregate happens only inside this class.
 */
@Entity
@Table(name = "identity_accounts")
@Getter
@Setter
@NoArgsConstructor
public class AccountJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "photo_url")
    private String photoUrl;

    private String address;

    private String dni;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Account.Status status;

    @Column(name = "verification_code", length = 6)
    private String verificationCode;

    @Column(name = "verification_expires_at")
    private OffsetDateTime verificationExpiresAt;

    @Column(name = "verification_attempts", nullable = false)
    private int verificationAttempts;

    @Column(name = "verification_resends", nullable = false)
    private int verificationResends;

    @Column(name = "verification_sent_at")
    private OffsetDateTime verificationSentAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static AccountJpaEntity fromDomain(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.setId(account.getId());
        entity.setEmail(account.getEmail());
        entity.setPasswordHash(account.getPasswordHash());
        entity.setName(account.getName());
        entity.setLastName(account.getLastName());
        entity.setPhotoUrl(account.getPhotoUrl());
        entity.setAddress(account.getAddress());
        entity.setDni(account.getDni());
        entity.setPhone(account.getPhone());
        entity.setRole(account.getRole());
        entity.setStatus(account.getStatus());
        entity.setVerificationCode(account.getVerificationCode());
        entity.setVerificationExpiresAt(toJdbc(account.getVerificationExpiresAt()));
        entity.setVerificationAttempts(account.getVerificationAttempts());
        entity.setVerificationResends(account.getVerificationResends());
        entity.setVerificationSentAt(toJdbc(account.getVerificationSentAt()));
        entity.setVerifiedAt(toJdbc(account.getVerifiedAt()));
        entity.setCreatedAt(toJdbc(account.getCreatedAt()));
        entity.setUpdatedAt(toJdbc(account.getUpdatedAt()));
        return entity;
    }

    public Account toDomain() {
        return Account.restore(id, email, passwordHash, name, lastName, photoUrl, address, dni, phone,
                role, status, verificationCode, toInstant(verificationExpiresAt), verificationAttempts,
                verificationResends, toInstant(verificationSentAt), toInstant(verifiedAt),
                toInstant(createdAt), toInstant(updatedAt));
    }

    static OffsetDateTime toJdbc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
