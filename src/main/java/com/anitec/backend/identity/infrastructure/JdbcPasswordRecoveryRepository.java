package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.PasswordRecovery;
import com.anitec.backend.identity.domain.PasswordRecoveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.sql.*;
import java.util.*;

@Repository
public class JdbcPasswordRecoveryRepository implements PasswordRecoveryRepository {
    private final JdbcTemplate jdbc;
    public JdbcPasswordRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant time) { return time == null ? null : Timestamp.from(time); }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        Timestamp value = r.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    @Override public Optional<PasswordRecovery> find(UUID accountId) {
        return jdbc.query("SELECT * FROM identity_password_recovery WHERE account_id=?", (r,n) ->
            new PasswordRecovery(r.getObject("account_id", UUID.class), instant(r,"window_started_at"),
                r.getInt("emission_count"), instant(r,"last_issued_at"), r.getObject("emission_id", UUID.class),
                r.getBytes("code_verifier"), instant(r,"expires_at"), r.getInt("failed_attempts"),
                instant(r,"consumed_at"), instant(r,"invalidated_at")), accountId).stream().findFirst();
    }
    @Override public void save(PasswordRecovery r) {
        jdbc.update("""
            INSERT INTO identity_password_recovery(account_id,purpose,window_started_at,emission_count,last_issued_at,
                emission_id,code_verifier,expires_at,failed_attempts,consumed_at,invalidated_at)
            VALUES (?,'PASSWORD_RECOVERY',?,?,?,?,?,?,?,?,?)
            ON CONFLICT(account_id) DO UPDATE SET window_started_at=excluded.window_started_at,
                emission_count=excluded.emission_count,last_issued_at=excluded.last_issued_at,emission_id=excluded.emission_id,
                code_verifier=excluded.code_verifier,expires_at=excluded.expires_at,failed_attempts=excluded.failed_attempts,
                consumed_at=excluded.consumed_at,invalidated_at=excluded.invalidated_at
            """, r.accountId(),ts(r.windowStartedAt()),r.emissions(),ts(r.lastIssuedAt()),r.emissionId(),r.verifier(),
                ts(r.expiresAt()),r.failedAttempts(),ts(r.consumedAt()),ts(r.invalidatedAt()));
    }
}
