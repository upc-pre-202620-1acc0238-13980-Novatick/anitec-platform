package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Plan;
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
import java.util.UUID;

/** Persistence model for {@code subscriptions_plans} (Flyway V5). */
@Entity
@Table(name = "subscriptions_plans")
@Getter
@Setter
@NoArgsConstructor
public class PlanJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    private Plan.PlanType type;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "billing_period", nullable = false, length = 20)
    private String billingPeriod;

    @Column(name = "capacity_limit", nullable = false)
    private int capacityLimit;

    @Column(nullable = false)
    private boolean active;

    public static PlanJpaEntity fromDomain(Plan plan) {
        PlanJpaEntity entity = new PlanJpaEntity();
        entity.setId(plan.getId());
        entity.setName(plan.getName());
        entity.setProfile(plan.getProfile());
        entity.setType(plan.getType());
        entity.setPrice(plan.getPrice());
        entity.setCurrency(plan.getCurrency());
        entity.setBillingPeriod(plan.getBillingPeriod());
        entity.setCapacityLimit(plan.getCapacityLimit());
        entity.setActive(plan.isActive());
        return entity;
    }

    public Plan toDomain() {
        return Plan.restore(id, name, profile, type, price, currency, billingPeriod, capacityLimit, active);
    }
}
