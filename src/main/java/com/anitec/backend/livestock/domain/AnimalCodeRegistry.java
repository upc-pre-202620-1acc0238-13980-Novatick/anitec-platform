package com.anitec.backend.livestock.domain;

import java.util.UUID;

/** Owner-scoped allocation lock; reservations and audit are persisted atomically with the animal. */
public interface AnimalCodeRegistry {
    void lockOwner(UUID ownerId);
    String nextAvailable(UUID ownerId);
    boolean reservedByOther(UUID ownerId, String code, UUID animalId);
}
