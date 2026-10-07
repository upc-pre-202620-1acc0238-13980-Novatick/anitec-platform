package com.anitec.backend.livestock.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data repository for {@code livestock_species}. */
public interface SpeciesJpaRepository extends JpaRepository<SpeciesJpaEntity, UUID> {
}
