package com.anitec.backend;

import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.*;
import com.anitec.backend.subscriptions.infrastructure.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionSimulationRegressionTests {
    @Test void stagingCannotSelectSimulatorEvenWithAnAuthorizedLocalProfile() {
        var e=new MockEnvironment().withProperty("payments.simulation-enabled","true");
        e.setActiveProfiles("staging","dev","test");
        assertThat(new PaymentGatewayConfiguration().paymentGateway(e)).isInstanceOf(UnavailablePaymentGateway.class);
    }

    @Test void schedulerNeverRenewsAnUnaccreditedPeriod() {
        var plans=mock(PlanRepository.class);var subscriptions=mock(SubscriptionRepository.class);
        var payments=mock(PaymentRepository.class);var events=mock(DomainEventDispatcher.class);
        var free=Plan.restore(UUID.randomUUID(),"Free",Role.GANADERO,Plan.PlanType.GRATUITO,BigDecimal.ZERO,"PEN","MENSUAL",15,true);
        var now=Instant.now();var account=UUID.randomUUID();
        var due=Subscription.restore(UUID.randomUUID(),account,Role.GANADERO,UUID.randomUUID(),Plan.PlanType.PREMIUM,
                Subscription.Status.ACTIVA,true,150,2,now.minusSeconds(3000),now.minusSeconds(1),now.minusSeconds(3000));
        when(subscriptions.findDueAccountIds(any())).thenReturn(List.of(account));
        when(subscriptions.findByAccountIdForUpdate(account)).thenReturn(Optional.of(due));
        when(plans.findFreeByProfile(Role.GANADERO)).thenReturn(Optional.of(free));
        var service=new SubscriptionService(plans,subscriptions,payments,new FakePaymentGateway(),events);
        assertThat(service.expireDueSubscriptions()).isEqualTo(1);
        assertThat(due.getPlanType()).isEqualTo(Plan.PlanType.GRATUITO);
        verifyNoInteractions(payments);
    }
}
