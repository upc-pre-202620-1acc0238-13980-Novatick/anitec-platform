package com.anitec.backend;

import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SubscriptionSimulationRestartTests {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    ServletWebServerApplicationContext start(String session){return (ServletWebServerApplicationContext)new SpringApplication(AnitecApplication.class).run(
            "--spring.profiles.active=test","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
            "--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
            "--spring.flyway.placeholders.demo_seed=false","--identity.sessions.recovery-key="+session,
            "--email.delivery.mode=disabled","--email.delivery.worker-enabled=false","--identity.password-recovery.enabled=false","--payments.simulation-enabled=true");}

    @Test void bothProfilesRetainAccreditedPeriodsAndResolvedPaymentsAcrossRestart(){
        var session=EmailCryptoSettingsTests.key();var accounts=new ArrayList<UUID>();var views=new ArrayList<SubscriptionService.SubscriptionView>();var refs=new ArrayList<String>();
        try(var first=start(session)){
            var identity=first.getBean(IdentityService.class);var service=first.getBean(SubscriptionService.class);
            for(var role:List.of(Role.GANADERO,Role.VETERINARIO)){
                UUID account=identity.register(new IdentityService.RegisterCommand("Test","Restart",UUID.randomUUID()+"@example.invalid","Isolated-Password1!",role)).accountId();accounts.add(account);
                UUID plan=service.plansFor(role).stream().filter(p->p.type().equals("PREMIUM")).findFirst().orElseThrow().id();
                var checkout=service.requestPremium(account,plan);service.confirmPayment(account,checkout.checkoutId(),SubscriptionService.PaymentOutcome.APPROVED);
                var renewal=service.requestPremium(account,plan,true);service.confirmPayment(account,renewal.checkoutId(),SubscriptionService.PaymentOutcome.APPROVED);refs.add(renewal.checkoutId());
                service.cancelRenewal(account);views.add(service.mySubscription(account));
            }
        }
        try(var second=start(session)){
            var service=second.getBean(SubscriptionService.class);var jdbc=second.getBean(JdbcTemplate.class);
            for(int i=0;i<accounts.size();i++){
                assertThat(service.mySubscription(accounts.get(i))).isEqualTo(views.get(i));
                assertThat(service.confirmPayment(accounts.get(i),refs.get(i),SubscriptionService.PaymentOutcome.DECLINED).paymentStatus()).isEqualTo("APPROVED");
                assertThat(service.mySubscription(accounts.get(i))).isEqualTo(views.get(i));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments WHERE status='APPROVED'",Long.class)).isEqualTo(4);
        }
    }
}
