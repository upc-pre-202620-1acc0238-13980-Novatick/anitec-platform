package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter for {@link AnimalRepository}. The filtered search is
 * built with JPA Specifications so optional filters never leak into JPQL
 * string concatenation.
 */
@Component
public class AnimalRepositoryImpl implements AnimalRepository {

    private final AnimalJpaRepository jpa;

    public AnimalRepositoryImpl(AnimalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Animal save(Animal animal) {
        return jpa.save(AnimalJpaEntity.fromDomain(animal)).toDomain();
    }

    @Override
    public Optional<Animal> findById(UUID animalId) {
        return jpa.findById(animalId).map(AnimalJpaEntity::toDomain);
    }

    @Override
    public boolean existsByFarmIdAndCode(UUID farmId, String code, UUID excludedAnimalId) {
        if (excludedAnimalId == null) {
            return jpa.existsByFarmIdAndCode(farmId, code);
        }
        return jpa.existsByFarmIdAndCodeAndIdNot(farmId, code, excludedAnimalId);
    }

    @Override
    public List<Animal> search(Collection<UUID> ownerScope, UUID farmId, UUID speciesId,
                               Animal.Status status, String query) {
        return jpa.findAll(searchSpec(ownerScope, farmId, speciesId, status, query),
                        Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(AnimalJpaEntity::toDomain).toList();
    }

    @Override
    public long countActiveByOwners(Collection<UUID> ownerScope) {
        return jpa.count(searchSpec(ownerScope, null, null, Animal.Status.ACTIVO, null));
    }

    @Override
    public long countBySpeciesId(UUID speciesId) {
        return jpa.countBySpeciesId(speciesId);
    }

    @Override
    public long countByFarmId(UUID farmId) {
        return jpa.countByFarmId(farmId);
    }

    @Override
    public long countByOwnerId(UUID ownerId) {
        return jpa.countByOwnerId(ownerId);
    }

    private Specification<AnimalJpaEntity> searchSpec(Collection<UUID> ownerScope, UUID farmId, UUID speciesId,
                                                      Animal.Status status, String query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (ownerScope != null) {
                predicates.add(root.get("ownerId").in(ownerScope));
            }
            if (farmId != null) {
                predicates.add(cb.equal(root.get("farmId"), farmId));
            }
            if (speciesId != null) {
                predicates.add(cb.equal(root.get("speciesId"), speciesId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("name")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
