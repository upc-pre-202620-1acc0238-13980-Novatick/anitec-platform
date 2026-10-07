package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.Payment;
import com.anitec.backend.subscriptions.domain.PaymentRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link PaymentRepository}. */
@Component
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpa;

    public PaymentRepositoryImpl(PaymentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Payment save(Payment payment) {
        return jpa.save(PaymentJpaEntity.fromDomain(payment)).toDomain();
    }

    @Override
    public Optional<Payment> findByGatewayRef(String gatewayRef) {
        return jpa.findByGatewayRef(gatewayRef).map(PaymentJpaEntity::toDomain);
    }
}
