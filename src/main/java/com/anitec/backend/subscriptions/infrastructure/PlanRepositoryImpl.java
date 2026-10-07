package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Plan;
import com.anitec.backend.subscriptions.domain.PlanRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link PlanRepository}. */
@Component
public class PlanRepositoryImpl implements PlanRepository {

    private final PlanJpaRepository jpa;

    public PlanRepositoryImpl(PlanJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Plan save(Plan plan) {
        return jpa.save(PlanJpaEntity.fromDomain(plan)).toDomain();
    }

    @Override
    public Optional<Plan> findById(UUID planId) {
        return jpa.findById(planId).map(PlanJpaEntity::toDomain);
    }

    @Override
    public List<Plan> findByProfile(Role profile) {
        return jpa.findByProfileOrderByPriceAsc(profile).stream().map(PlanJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Plan> findFreeByProfile(Role profile) {
        return jpa.findByProfileAndType(profile, Plan.PlanType.GRATUITO).map(PlanJpaEntity::toDomain);
    }

    @Override
    public List<Plan> findAll() {
        return jpa.findAll().stream().map(PlanJpaEntity::toDomain).toList();
    }
}
