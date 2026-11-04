package com.anitec.backend;

import com.anitec.backend.identity.infrastructure.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.mock.env.MockEnvironment;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationTests {
    private static final String TEST_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();

    @Configuration(proxyBeanMethods = false)
    static class MinimalApplication {
    }

    private ConfigurableApplicationContext start(String... overrides) {
        SpringApplication application = new SpringApplication(MinimalApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=prod", "--spring.main.banner-mode=off",
                "--logging.level.root=OFF", "--jwt.secret=" + TEST_SECRET,
                "--spring.flyway.placeholders.demo_seed=false", "--payments.simulation-enabled=false"));
        var resolved = new LinkedHashMap<String, String>();
        args.addAll(List.of(overrides));
        for (String argument : args) {
            String[] parts = argument.split("=", 2);
            resolved.put(parts[0], parts[1]);
        }
        return application.run(resolved.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private void rejects(String key, String value, String message) {
        assertThatThrownBy(() -> {
            try (var ignored = start("--" + key + "=" + value)) {
                // A rejected environment must never create an application context.
            }
        }).hasMessageContaining(message);
    }

    @Test
    void productionRejectsMissingJwtSecret() {
        rejects("jwt.secret", "", "jwt.secret");
    }

    @Test
    void productionRejectsDevelopmentJwtSecret() {
        rejects("jwt.secret", "anitec-dev-only-secret-key-change-me-32bytes-min!!", "jwt.secret");
    }

    @Test
    void productionRejectsShortJwtSecret() {
        rejects("jwt.secret", "short", "jwt.secret");
    }

    @Test
    void productionDoesNotInheritJwtDevelopmentDefaultFromBaseYaml() {
        var application = new SpringApplication(MinimalApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> {
            try (var ignored = application.run("--spring.profiles.active=prod", "--JWT_SECRET=",
                    "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            }
        }).hasMessageContaining("jwt.secret");
    }

    @Test
    void productionRejectsDemoSeeds() {
        rejects("spring.flyway.placeholders.demo_seed", "true", "demo_seed");
    }

    @Test
    void productionRejectsSimulationEvenWithDevelopmentAndTestProfiles() {
        assertThatThrownBy(() -> {
            try (var ignored = start("--spring.profiles.active=prod,dev,test",
                    "--payments.simulation-enabled=true")) {
            }
        }).hasMessageContaining("payments.simulation-enabled");
    }

    @Test
    void productionAcceptsExplicitSafeConfiguration() {
        try (var context = start()) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void missingEmailProviderDoesNotReportDelivery() {
        assertThat(new ResendEmailSender("", "", new MockEnvironment())
                .send("unused@example.invalid", "test", "private body"))
                .isFalse();
    }
}
