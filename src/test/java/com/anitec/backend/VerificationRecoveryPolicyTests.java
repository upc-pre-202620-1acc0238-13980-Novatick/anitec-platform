package com.anitec.backend;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.identity.domain.VerificationThrottle;
import com.anitec.backend.identity.interfaceapi.VerificationOriginLimiter;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class VerificationRecoveryPolicyTests {
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private Account pending() { return Account.create("window@example.invalid", "unused", "Test", "Policy", Role.GANADERO, start); }

    @Test void exhaustedPendingAccountRecoversExactlyAtTwentyFourHours() {
        var account = pending();
        for (int i=1;i<=5;i++) account.issueVerificationCode(start.plusSeconds(60L*i));
        assertThatCode(() -> account.issueVerificationCode(start.plusSeconds(86400))).doesNotThrowAnyException();
        assertThat(account.getVerificationResends()).isEqualTo(1);
        assertThat(account.getVerificationWindowStartedAt()).isEqualTo(start.plusSeconds(86400));
    }

    @Test void initialEmissionIsFreeAndSixthResendDoesNotChangeAnything() {
        var a=pending();assertThat(a.getVerificationResends()).isZero();
        assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start);
        for(int i=1;i<=5;i++) a.issueVerificationCode(start.plusSeconds(i*60L));
        String code=a.getVerificationCode();var sent=a.getVerificationSentAt();
        assertThatThrownBy(()->a.issueVerificationCode(start.plusSeconds(86399)))
                .isInstanceOfSatisfying(VerificationThrottle.class,e->{
                    assertThat(e.errorCode()).isEqualTo("VERIFICATION_RESEND_LIMIT");
                    assertThat(e.retryAt()).isEqualTo(start.plusSeconds(86400));
                });
        assertThat(a.getVerificationCode()).isEqualTo(code);assertThat(a.getVerificationResends()).isEqualTo(5);
        assertThat(a.getVerificationSentAt()).isEqualTo(sent);assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start);
    }
    @Test void laterWindowStartsAtFirstAcceptedRequestAndHasNoFreeInitialEmission() {
        var a=pending();a.issueVerificationCode(start.plusSeconds(90000));
        assertThat(a.getVerificationResends()).isEqualTo(1);
        assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start.plusSeconds(90000));
        for(int i=1;i<=4;i++) a.issueVerificationCode(start.plusSeconds(90000+i*60L));
        assertThatThrownBy(()->a.issueVerificationCode(start.plusSeconds(91000))).isInstanceOf(VerificationThrottle.class);
    }
    @Test void cooldownAndAvailabilitySurviveWindowBoundary() {
        var a=pending();for(int i=1;i<=4;i++) a.issueVerificationCode(start.plusSeconds(i*60L));
        a.issueVerificationCode(start.plusSeconds(86390));String code=a.getVerificationCode();
        assertThat(a.nextVerificationResendAt(start.plusSeconds(86395))).isEqualTo(start.plusSeconds(86450));
        assertThatThrownBy(()->a.issueVerificationCode(start.plusSeconds(86400))).isInstanceOf(VerificationThrottle.class);
        assertThat(a.getVerificationCode()).isEqualTo(code);assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start);
        a.issueVerificationCode(start.plusSeconds(86450));assertThat(a.getVerificationResends()).isEqualTo(1);
        assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start.plusSeconds(86450));
    }
    @Test void fifthCodeKeepsTenMinuteValidityAcrossWindowEndAndCanCompleteVerification() {
        var a=pending();for(int i=1;i<=4;i++) a.issueVerificationCode(start.plusSeconds(i*60L));
        a.issueVerificationCode(start.plusSeconds(86390));String code=a.getVerificationCode();
        assertThat(a.getVerificationExpiresAt()).isEqualTo(start.plusSeconds(86990));
        a.verifyEmail(code,start.plusSeconds(86410));assertThat(a.canSignIn()).isTrue();
        assertThat(a.getVerificationCode()).isNull();
        assertThatThrownBy(()->a.issueVerificationCode(start.plusSeconds(86500))).isInstanceOf(DomainException.class);
    }
    @Test void expiredLastCodeIsNotRevivedAtWindowEnd() {
        var a=pending();for(int i=1;i<=5;i++) a.issueVerificationCode(start.plusSeconds(i*60L));
        String code=a.getVerificationCode();
        assertThatThrownBy(()->a.verifyEmail(code,start.plusSeconds(86400)))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.errorCode()).isEqualTo("CODE_EXPIRED"));
        assertThat(a.getVerificationResends()).isEqualTo(5);assertThat(a.getVerificationWindowStartedAt()).isEqualTo(start);
    }
    @Test void exhaustedAttemptsStayExhaustedUntilAnAcceptedNewEmission() {
        var a=pending();String wrong="000000".equals(a.getVerificationCode())?"000001":"000000";
        for(int i=0;i<5;i++) assertThatThrownBy(()->a.verifyEmail(wrong,start.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThat(a.getVerificationAttempts()).isEqualTo(5);
        assertThatThrownBy(()->a.verifyEmail("123456",start.plusSeconds(86400)))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.errorCode()).isEqualTo("CODE_ATTEMPTS_EXCEEDED"));
        assertThat(a.getVerificationAttempts()).isEqualTo(5);
        a.issueVerificationCode(start.plusSeconds(86400));assertThat(a.getVerificationAttempts()).isZero();
        assertThat(a.getVerificationResends()).isEqualTo(1);
    }
    @Test void originLimitIsIndependentOfAccountsAndUsesServerClock() {
        var limiter=new VerificationOriginLimiter(Clock.fixed(start, ZoneOffset.UTC),2);
        limiter.check("origin","resend");limiter.check("origin","resend");
        assertThatThrownBy(()->limiter.check("origin","resend"))
                .isInstanceOfSatisfying(VerificationThrottle.class,e->assertThat(e.retryAt()).isEqualTo(start.plusSeconds(60)));
        limiter.check("another-origin","resend");limiter.check("origin","verify");
    }
}
