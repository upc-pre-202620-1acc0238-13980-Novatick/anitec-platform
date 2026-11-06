package com.anitec.backend.shared.infrastructure;

import com.anitec.backend.shared.infrastructure.security.StagingAccessKey;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Fail before beans/Flyway; staging privacy policy cannot be relaxed by property overrides. */
public class StagingSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 3; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!StagingAccessKey.active(environment)) return;
        StagingAccessKey.validated(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("stagingPrivacyPolicy", Map.of(
                "management.endpoint.health.show-details", "never",
                "management.endpoint.health.show-components", "never",
                "management.endpoint.health.probes.enabled", "false",
                "management.endpoints.web.exposure.include", "health",
                "springdoc.swagger-ui.enabled", "false",
                "springdoc.api-docs.enabled", "false")));
    }
}
