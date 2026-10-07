package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Species;
import com.anitec.backend.livestock.domain.SpeciesRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure adapter for {@link SpeciesRepository}. */
@Component
public class SpeciesRepositoryImpl implements SpeciesRepository {

    private final SpeciesJpaRepository jpa;

    public SpeciesRepositoryImpl(SpeciesJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Species save(Species species) {
        return jpa.save(SpeciesJpaEntity.fromDomain(species)).toDomain();
    }

    @Override
    public Optional<Species> findById(UUID speciesId) {
        return jpa.findById(speciesId).map(SpeciesJpaEntity::toDomain);
    }

    @Override
    public List<Species> findAll() {
        return jpa.findAll(SortHelper.byCreatedAt()).stream().map(SpeciesJpaEntity::toDomain).toList();
    }

    @Override
    public void delete(UUID speciesId) {
        jpa.deleteById(speciesId);
    }
}
