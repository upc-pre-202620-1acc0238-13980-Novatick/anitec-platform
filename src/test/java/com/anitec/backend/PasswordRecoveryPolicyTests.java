package com.anitec.backend;
import com.anitec.backend.identity.domain.PasswordRecovery;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PasswordRecoveryPolicyTests {
 Instant start=Instant.parse("2035-01-01T00:00:00Z");
 PasswordRecovery first(){return PasswordRecovery.issue(UUID.randomUUID(),null,UUID.randomUUID(),new byte[32],start);}
 @Test void firstCountsAndWindowIsFixed(){var s=first();assertThat(s.emissions()).isEqualTo(1);var n=PasswordRecovery.issue(s.accountId(),s,UUID.randomUUID(),new byte[32],start.plusSeconds(60));assertThat(n.windowStartedAt()).isEqualTo(start);assertThat(n.emissions()).isEqualTo(2);}
 @Test void noAutomaticResetOnWindowExpiry(){var s=first();assertThat(s.allowsEmission(start.plusSeconds(86400))).isTrue();assertThat(s.emissions()).isEqualTo(1);assertThat(s.windowStartedAt()).isEqualTo(start);}
 @Test void activeCodeSurvivesQuotaExhaustion(){var s=first();for(int i=1;i<5;i++)s=PasswordRecovery.issue(s.accountId(),s,UUID.randomUUID(),new byte[32],start.plusSeconds(i*60));assertThat(s.emissions()).isEqualTo(5);assertThat(s.usable(start.plusSeconds(300))).isTrue();assertThat(s.allowsEmission(start.plusSeconds(300))).isFalse();}
 @Test void successfulConsumptionDoesNotResetQuota(){var s=first().consume(start.plusSeconds(1));assertThat(s.verifier()).isNull();assertThat(s.emissions()).isEqualTo(1);assertThat(s.windowStartedAt()).isEqualTo(start);assertThat(s.usable(start.plusSeconds(2))).isFalse();}
 @Test void fifthWrongAttemptInvalidatesOnlyCode(){var s=first();for(int i=0;i<5;i++)s=s.failed(start.plusSeconds(i+1));assertThat(s.failedAttempts()).isEqualTo(5);assertThat(s.verifier()).isNull();assertThat(s.emissions()).isEqualTo(1);}
 @Test void newIssuanceResetsAttemptsNotWindow(){var s=first().failed(start.plusSeconds(1));var next=PasswordRecovery.issue(s.accountId(),s,UUID.randomUUID(),new byte[32],start.plusSeconds(60));assertThat(next.failedAttempts()).isZero();assertThat(next.windowStartedAt()).isEqualTo(start);assertThat(next.emissions()).isEqualTo(2);}
 @Test void verifierBytesCannotBeMutatedThroughAccessor(){byte[] mac=new byte[32];var s=PasswordRecovery.issue(UUID.randomUUID(),null,UUID.randomUUID(),mac,start);mac[0]=1;byte[] read=s.verifier();read[0]=2;assertThat(s.verifier()[0]).isZero();}
 @Test void exactExpirationIsNotUsable(){var s=first();assertThat(s.usable(start.plusSeconds(599))).isTrue();assertThat(s.usable(start.plusSeconds(600))).isFalse();}
}
