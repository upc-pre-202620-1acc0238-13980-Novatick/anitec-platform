package com.anitec.backend;

import com.anitec.backend.shared.infrastructure.security.StagingAccessGateFilter;
import com.anitec.backend.shared.infrastructure.security.StagingAccessKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class StagingAccessGateTests {
    static String key() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getEncoder().encodeToString(value); }
    private final String secret = key();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    MockEnvironment environment() {
        var env = new MockEnvironment().withProperty("staging.access.key", secret);
        env.setActiveProfiles("prod", "staging");
        return env;
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"invalid", " ", "AAAA", "not-base64!", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsAbsentOrMalformedHeadersWithoutInvokingNextFilter(String value) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        if (value != null) request.addHeader(StagingAccessGateFilter.HEADER, value);
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new StagingAccessGateFilter(environment(), mapper).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(response.getContentAsString()).path("errorCode").asText()).isEqualTo("STAGING_ACCESS_DENIED");
        assertThat(response.getContentAsString().contains(secret)).isFalse();
        assertThat(chain.getRequest()).isNull();
    }

    @Test void rejectsWrongCanonicalKeyAndDuplicateOrMergedHeaders() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            var request = new MockHttpServletRequest("GET", "/api/v1/account/me");
            request.addHeader(StagingAccessGateFilter.HEADER, variant == 0 ? key() : secret);
            if (variant == 1) request.addHeader(StagingAccessGateFilter.HEADER, secret);
            if (variant == 2) request.addHeader(StagingAccessGateFilter.HEADER, "");
            if (variant == 3) { request.removeHeader(StagingAccessGateFilter.HEADER); request.addHeader(StagingAccessGateFilter.HEADER, secret + "," + secret); }
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            new StagingAccessGateFilter(environment(), mapper).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403); assertThat(chain.getRequest()).isNull();
        }
    }

    @Test void onlyExactGetHealthIsPublic() throws Exception {
        for (String method : new String[]{"GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE"}) {
            for (String path : new String[]{"/actuator/health", "/actuator/health/", "/actuator/health/readiness", "/actuator/info", "/actuator/health%2freadiness"}) {
                var request = new MockHttpServletRequest(method, path);
                var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
                new StagingAccessGateFilter(environment(), mapper).doFilter(request, response, chain);
                if (method.equals("GET") && path.equals("/actuator/health")) assertThat(chain.getRequest()).isNotNull();
                else { assertThat(response.getStatus()).isEqualTo(403); assertThat(chain.getRequest()).isNull(); }
            }
        }
    }

    @Test void validKeyPassesToExistingChain() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/account/me");
        request.addHeader(StagingAccessGateFilter.HEADER, secret);
        var chain = new MockFilterChain();
        new StagingAccessGateFilter(environment(), mapper).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest @ValueSource(strings = {"prod", "dev", "test"})
    void outsideStagingDoesNotValidateKeyOrRestrictRequests(String profile) throws Exception {
        var env = new MockEnvironment().withProperty("staging.access.key", "invalid"); env.setActiveProfiles(profile);
        var chain = new MockFilterChain(); var request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        new StagingAccessGateFilter(env, mapper).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test void rejectsReuseOfEverySecurityKeyAliasIncludingLiteralJwt() {
        for (String property : new String[]{"jwt.secret", "JWT_SECRET", "identity.sessions.recovery-key", "SESSION_RECOVERY_KEY",
                "identity.email-verification.hmac-key", "EMAIL_VERIFICATION_HMAC_KEY", "identity.password-recovery.hmac-key",
                "PASSWORD_RECOVERY_HMAC_KEY", "email.delivery.encryption-key", "EMAIL_OUTBOX_ENCRYPTION_KEY"}) {
            assertThatThrownBy(() -> StagingAccessKey.validated(environment().withProperty(property, secret)))
                    .hasMessageContaining("independent");
        }
        byte[] ascii = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var env = environment().withProperty("staging.access.key", Base64.getEncoder().encodeToString(ascii))
                .withProperty("jwt.secret", new String(ascii, java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StagingAccessKey.validated(env)).hasMessageContaining("independent");
    }

    @Test void rejectsDegenerateKeyAndNonCanonicalOrWhitespaceEncoding() {
        String[] invalid = {Base64.getEncoder().encodeToString(new byte[32]), secret.stripTrailing() + " ",
                secret.substring(0, 43), Base64.getEncoder().encodeToString(new byte[31]), Base64.getEncoder().encodeToString(new byte[33])};
        for (String value : invalid) assertThatThrownBy(() -> StagingAccessKey.validated(environment().withProperty("staging.access.key", value)))
                .isInstanceOf(IllegalStateException.class);
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        String nonCanonical=secret.substring(0,42)+alphabet.charAt(alphabet.indexOf(secret.charAt(42))+1)+"=";
        assertThatThrownBy(() -> StagingAccessKey.validated(environment().withProperty("staging.access.key",nonCanonical)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Configuration(proxyBeanMethods = false) static class MinimalApplication { }
    private org.springframework.context.ConfigurableApplicationContext start(String... overrides) {
        var app = new SpringApplication(MinimalApplication.class); app.setWebApplicationType(WebApplicationType.NONE); app.setLogStartupInfo(false);
        var args = new java.util.LinkedHashMap<String, String>();
        for (String arg : new String[]{"--spring.profiles.active=prod,staging", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                "--jwt.secret=" + key(), "--staging.access.key=" + secret, "--spring.flyway.placeholders.demo_seed=false",
                "--payments.simulation-enabled=false"}) { String[] parts=arg.split("=",2);args.put(parts[0],parts[1]); }
        for (String arg : overrides) { String[] parts=arg.split("=",2);args.put(parts[0],parts[1]); }
        return app.run(args.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toArray(String[]::new));
    }

    @ParameterizedTest @ValueSource(strings = {"", "bad", "AAAA", "${missing-staging-value}"})
    void startupFailsWithMissingOrInvalidSecret(String invalid) {
        assertThatThrownBy(() -> { try (var ignored = start("--staging.access.key=" + invalid)) { } })
                .hasMessageContaining("Staging requires");
    }

    @Test void stagingAloneCannotBypassProductionChecks() {
        assertThatThrownBy(() -> { try (var ignored = start("--spring.profiles.active=staging")) { } })
                .hasMessageContaining("prod profile");
    }

    @Test void combinedProfilesStillRejectUnsafeProductionConfiguration() {
        for (String value : new String[]{"--jwt.secret=anitec-dev-only-secret-key-change-me-32bytes-min!!",
                "--spring.flyway.placeholders.demo_seed=true", "--payments.simulation-enabled=true"}) {
            assertThatThrownBy(() -> { try (var ignored = start("--spring.profiles.active=prod,staging,dev,test", value)) { } })
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void startupEnforcesHealthPrivacyAndDisablesDocsDespitePropertyOverrides() {
        try (var context = start("--management.endpoint.health.show-details=always", "--management.endpoint.health.show-components=always",
                "--management.endpoints.web.exposure.include=*", "--springdoc.api-docs.enabled=true", "--springdoc.swagger-ui.enabled=true")) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        }
    }
}
