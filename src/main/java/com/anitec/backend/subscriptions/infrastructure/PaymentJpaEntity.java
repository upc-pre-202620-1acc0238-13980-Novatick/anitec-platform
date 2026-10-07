package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for {@code subscriptions_payments} (Flyway V5). */
@Entity
@Table(name = "subscriptions_payments")
@Getter
@Setter
@NoArgsConstructor
public class PaymentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "gateway_ref", nullable = false, unique = true, length = 100)
    private String gatewayRef;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Payment.Status status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static PaymentJpaEntity fromDomain(Payment payment) {
        PaymentJpaEntity entity = new PaymentJpaEntity();
        entity.setId(payment.getId());
        entity.setSubscriptionId(payment.getSubscriptionId());
        entity.setAccountId(payment.getAccountId());
        entity.setPlanId(payment.getPlanId());
        entity.setGatewayRef(payment.getGatewayRef());
        entity.setAmount(payment.getAmount());
        entity.setCurrency(payment.getCurrency());
        entity.setStatus(payment.getStatus());
        entity.setCreatedAt(SubscriptionJpaEntity.toJdbc(payment.getCreatedAt()));
        entity.setUpdatedAt(SubscriptionJpaEntity.toJdbc(payment.getUpdatedAt()));
        return entity;
    }

    public Payment toDomain() {
        return Payment.restore(id, subscriptionId, accountId, planId, gatewayRef, amount, currency,
                status, SubscriptionJpaEntity.toInstant(createdAt),
                SubscriptionJpaEntity.toInstant(updatedAt));
    }
}
