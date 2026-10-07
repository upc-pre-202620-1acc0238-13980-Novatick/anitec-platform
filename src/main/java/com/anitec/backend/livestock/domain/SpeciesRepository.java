package com.anitec.backend.livestock.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository ports of the Livestock Management context (report contracts). */
public interface SpeciesRepository {

    Species save(Species species);

    Optional<Species> findById(UUID speciesId);

    List<Species> findAll();

    void delete(UUID speciesId);
}
