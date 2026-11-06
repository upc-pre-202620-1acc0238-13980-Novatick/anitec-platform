package com.anitec.backend;

import com.anitec.backend.subscriptions.infrastructure.*;
import com.anitec.backend.subscriptions.domain.Subscription;
import com.anitec.backend.subscriptions.domain.Plan;
import com.anitec.backend.shared.domain.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SubscriptionSimulationPolicyTests {
    @ParameterizedTest @CsvSource({"prod","staging","prod;dev","staging;test","staging;dev","prod;staging;dev;test","qa"})
    void forbiddenProfilesNeverExposeClientApproval(String profiles){
        var env=new MockEnvironment().withProperty("payments.simulation-enabled","true");env.setActiveProfiles(profiles.split(";"));
        var gateway=new PaymentGatewayConfiguration().paymentGateway(env);assertThat(gateway).isInstanceOf(UnavailablePaymentGateway.class);
        assertThatThrownBy(gateway::requireSimulatedConfirmation).hasMessageContaining("no están disponibles");
        assertThatThrownBy(()->gateway.createCheckout(UUID.randomUUID(),null)).isInstanceOf(com.anitec.backend.shared.domain.DomainException.class);
    }
    @Test void expiryIncludesTheExactBoundary(){
        var t=Instant.parse("2026-01-31T12:00:00Z");var plan=Plan.restore(UUID.randomUUID(),"Premium",Role.GANADERO,Plan.PlanType.PREMIUM,new BigDecimal("19.90"),"PEN","MENSUAL",150,true);
        var s=Subscription.createForAccount(UUID.randomUUID(),Role.GANADERO,plan,t);s.activatePremium(plan,t);
        assertThat(s.getEndsAt()).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"));assertThat(s.isDue(s.getEndsAt().minusNanos(1))).isFalse();assertThat(s.isDue(s.getEndsAt())).isTrue();
        assertThatThrownBy(()->s.renewPremium(s.getEndsAt())).isInstanceOf(com.anitec.backend.shared.domain.DomainException.class);
    }
}
