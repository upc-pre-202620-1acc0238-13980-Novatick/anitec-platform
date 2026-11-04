package com.anitec.backend;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class AccountVerificationPolicyTests {
    private Account pending(Instant now) {
        return Account.create("policy@example.invalid", "unused", "Test", "Policy", Role.GANADERO, now);
    }

    @Test
    void generatedCodeHasSixAsciiDigitsRegardlessOfServerLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar"));
            assertThat(pending(Instant.now()).getVerificationCode().matches("[0-9]{6}")).isTrue();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void codeExpiresAtTheExactTenMinuteBoundary() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var account = pending(now);
        assertThat(account.getVerificationExpiresAt()).isEqualTo(now.plusSeconds(600));
        assertThatThrownBy(() -> account.verifyEmail(account.getVerificationCode(), now.plusSeconds(600)))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.errorCode()).isEqualTo("CODE_EXPIRED"));
        assertThat(account.isVerified()).isFalse();
    }

    @Test
    void resendAllowedAtExactCooldownBoundaryWithFreshTtl() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var account = pending(now);
        String previousCode = account.getVerificationCode();
        assertThatThrownBy(() -> account.issueVerificationCode(now.plusSeconds(59))).isInstanceOf(DomainException.class);
        account.issueVerificationCode(now.plusSeconds(60));
        assertThat(previousCode.equals(account.getVerificationCode())).isFalse();
        assertThat(account.getVerificationResends()).isEqualTo(1);
        assertThat(account.getVerificationExpiresAt()).isEqualTo(now.plusSeconds(660));
    }
}
