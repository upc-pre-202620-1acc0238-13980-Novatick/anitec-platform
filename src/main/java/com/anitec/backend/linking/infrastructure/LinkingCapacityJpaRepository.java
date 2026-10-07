package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.LinkingCapacity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@code linking_capacity}. */
public interface LinkingCapacityJpaRepository extends JpaRepository<LinkingCapacityJpaEntity, UUID> {

    Optional<LinkingCapacityJpaEntity> findByVetId(UUID vetId);
}
