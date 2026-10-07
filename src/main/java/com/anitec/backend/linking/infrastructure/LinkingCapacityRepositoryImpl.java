package com.anitec.backend.linking.infrastructure;

import com.anitec.backend.linking.domain.LinkingCapacity;
import com.anitec.backend.linking.domain.LinkingCapacityRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link LinkingCapacityRepository}. */
@Component
public class LinkingCapacityRepositoryImpl implements LinkingCapacityRepository {

    private final LinkingCapacityJpaRepository jpa;

    public LinkingCapacityRepositoryImpl(LinkingCapacityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public LinkingCapacity save(LinkingCapacity capacity) {
        return jpa.save(LinkingCapacityJpaEntity.fromDomain(capacity)).toDomain();
    }

    @Override
    public Optional<LinkingCapacity> findByVeterinarianId(UUID veterinarianId) {
        return jpa.findByVetId(veterinarianId).map(LinkingCapacityJpaEntity::toDomain);
    }
}
