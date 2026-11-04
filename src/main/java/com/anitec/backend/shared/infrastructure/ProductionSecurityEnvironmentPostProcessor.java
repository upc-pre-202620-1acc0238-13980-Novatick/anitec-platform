package com.anitec.backend.shared.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;

/** Validates resolved production settings after config data, before any beans or Flyway. */
public class ProductionSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String DEVELOPMENT_JWT = "anitec-dev-only-secret-key-change-me-32bytes-min!!";
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String secret = environment.getProperty("jwt.secret", "");
        require(!secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length >= 32
                        && !secret.strip().equals(DEVELOPMENT_JWT),
                "jwt.secret must be explicitly configured with at least 32 bytes and must not use the development value");

        require("false".equalsIgnoreCase(environment.getProperty("spring.flyway.placeholders.demo_seed", "")),
                "spring.flyway.placeholders.demo_seed must be false in production");
        require(!environment.getProperty("payments.simulation-enabled", Boolean.class, false),
                "payments.simulation-enabled must be false in production, including mixed profiles");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

}
