package com.anitec.backend.subscriptions.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment record (idempotency key = gatewayRef, TS02). In this build the
 * "gateway" is the FakePaymentGateway adapter behind the PaymentGateway port.
 */
@Getter
public class Payment {

    public enum Status {PENDING, APPROVED, DECLINED}

    private UUID id;
    private UUID subscriptionId;
    private UUID accountId;
    private UUID planId;
    private String gatewayRef;
    private BigDecimal amount;
    private String currency;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {
    }

    private Payment(UUID id, UUID subscriptionId, UUID accountId, UUID planId, String gatewayRef,
                    BigDecimal amount, String currency, Status status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.accountId = accountId;
        this.planId = planId;
        this.gatewayRef = gatewayRef;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Payment pending(UUID subscriptionId, UUID accountId, UUID planId, String gatewayRef,
                                  BigDecimal amount, String currency, Instant now) {
        return new Payment(UUID.randomUUID(), subscriptionId, accountId, planId, gatewayRef,
                amount, currency, Status.PENDING, now, now);
    }

    public static Payment restore(UUID id, UUID subscriptionId, UUID accountId, UUID planId,
                                  String gatewayRef, BigDecimal amount, String currency,
                                  Status status, Instant createdAt, Instant updatedAt) {
        return new Payment(id, subscriptionId, accountId, planId, gatewayRef, amount, currency,
                status, createdAt, updatedAt);
    }

    /** @return true only when the pending payment actually changed (idempotent). */
    public boolean applyResult(Status result, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        this.status = result;
        this.updatedAt = now;
        return true;
    }

    public boolean belongsTo(UUID accountId) {
        return this.accountId != null && this.accountId.equals(accountId);
    }
}
