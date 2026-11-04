package com.anitec.backend.subscriptions.infrastructure;

import com.anitec.backend.subscriptions.domain.PaymentGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class PaymentGatewayConfiguration {
    @Bean
    public PaymentGateway paymentGateway(Environment environment) {
        boolean simulation = !environment.acceptsProfiles(Profiles.of("prod"))
                && environment.acceptsProfiles(Profiles.of("dev", "test"))
                && environment.getProperty("payments.simulation-enabled", Boolean.class, false);
        return simulation ? new FakePaymentGateway() : new UnavailablePaymentGateway();
    }
}
