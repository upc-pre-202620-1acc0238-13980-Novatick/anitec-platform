package com.anitec.backend.subscriptions.domain;

import com.anitec.backend.shared.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository ports of the Subscriptions context. */
public interface PlanRepository {


    Optional<Plan> findById(UUID planId);

    List<Plan> findByProfile(Role profile);

    Optional<Plan> findFreeByProfile(Role profile);

}
