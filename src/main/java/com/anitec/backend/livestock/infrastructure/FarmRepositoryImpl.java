package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Farm;
import com.anitec.backend.livestock.domain.FarmRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link FarmRepository}. */
@Component
public class FarmRepositoryImpl implements FarmRepository {

    private final FarmJpaRepository jpa;

    public FarmRepositoryImpl(FarmJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Farm save(Farm farm) {
        return jpa.save(FarmJpaEntity.fromDomain(farm)).toDomain();
    }

    @Override
    public Optional<Farm> findById(UUID farmId) {
        return jpa.findById(farmId).map(FarmJpaEntity::toDomain);
    }

    @Override
    public List<Farm> findByOwnerId(UUID ownerId) {
        return jpa.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream().map(FarmJpaEntity::toDomain).toList();
    }
}
