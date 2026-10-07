package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code subscriptions_plans}. */
public interface PlanJpaRepository extends JpaRepository<PlanJpaEntity, UUID> {

    List<PlanJpaEntity> findByProfileOrderByPriceAsc(Role profile);

    Optional<PlanJpaEntity> findByProfileAndType(Role profile, Plan.PlanType type);
}
