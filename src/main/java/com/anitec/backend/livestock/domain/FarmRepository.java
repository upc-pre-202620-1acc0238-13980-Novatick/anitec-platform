package com.anitec.backend.livestock.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository port for the Farm aggregate. */
public interface FarmRepository {

    Farm save(Farm farm);

    Optional<Farm> findById(UUID farmId);

    List<Farm> findByOwnerId(UUID ownerId);
}
