package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.Animal;
import com.anitec.backend.livestock.domain.AnimalRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.anitec.backend.shared.domain.DomainException;

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
        try {
            return jpa.saveAndFlush(AnimalJpaEntity.fromDomain(animal)).toDomain();
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw DomainException.conflict("ANIMAL_VERSION_CONFLICT", "El animal cambió; vuelva a consultar sus datos");
        } catch (DataIntegrityViolationException conflict) {
            String cause = String.valueOf(conflict.getMostSpecificCause().getMessage());
            if (cause.contains("uq_animal_codes_owner_code") || cause.contains("uq_livestock_animal_owner_code")) {
                throw DomainException.conflict("DUPLICATE_ANIMAL_CODE", "El código está reservado para otro animal de su inventario");
            }
            throw conflict;
        }
    }

    @Override
    public Optional<Animal> findById(UUID animalId) {
        return jpa.findById(animalId).map(AnimalJpaEntity::toDomain);
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
    public long countActiveInFarm(Collection<UUID> ownerScope, UUID farmId) {
        return jpa.count(searchSpec(ownerScope, farmId, null, Animal.Status.ACTIVO, null));
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
