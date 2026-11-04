package com.anitec.backend.livestock.infrastructure;

import com.anitec.backend.livestock.domain.AnimalCodeRegistry;
import com.anitec.backend.shared.domain.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.UUID;

@Component
public class AnimalCodeRegistryImpl implements AnimalCodeRegistry {
    private final JdbcTemplate jdbc;
    public AnimalCodeRegistryImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void lockOwner(UUID ownerId) {
        jdbc.update("INSERT INTO livestock_code_counters(owner_id) VALUES (?) ON CONFLICT DO NOTHING", ownerId);
        jdbc.queryForObject("SELECT next_value FROM livestock_code_counters WHERE owner_id=? FOR UPDATE", Long.class, ownerId);
    }

    @Override public String nextAvailable(UUID ownerId) {
        while (true) {
            Long next = jdbc.queryForObject("SELECT next_value FROM livestock_code_counters WHERE owner_id=?", Long.class, ownerId);
            if (next == null || next == Long.MAX_VALUE) {
                throw DomainException.conflict("ANIMAL_CODE_SEQUENCE_EXHAUSTED", "Se agotó la secuencia de códigos");
            }
            jdbc.update("UPDATE livestock_code_counters SET next_value=next_value+1 WHERE owner_id=?", ownerId);
            String code = String.format(Locale.ROOT, "ANI-%06d", next);
            if (!reservedByOther(ownerId, code, null)) return code;
        }
    }

    @Override public boolean reservedByOther(UUID ownerId, String code, UUID animalId) {
        return jdbc.query("SELECT animal_id FROM livestock_animal_codes WHERE owner_id=? AND code=?",
                (rs, row) -> rs.getObject(1, UUID.class), ownerId, code).stream()
                .anyMatch(id -> !id.equals(animalId));
    }
}
