package com.anitec.backend;

import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.domain.AccountRepository;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.shared.infrastructure.security.JwtAuthFilter;
import com.anitec.backend.shared.infrastructure.security.StagingAccessGateFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.CorsFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class StagingAccessGateIntegrationTests {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static ConfigurableApplicationContext context;
    static final String KEY = StagingAccessGateTests.key();
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;

    @BeforeAll static void start() {
        var app = new SpringApplication(AnitecApplication.class); app.setLogStartupInfo(false);
        context = app.run("--spring.profiles.active=prod,staging", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--logging.level.com.anitec.backend=ERROR",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--jwt.secret=" + StagingAccessGateTests.key(),
                "--staging.access.key=" + KEY, "--identity.sessions.recovery-key=" + StagingAccessGateTests.key(),
                "--spring.flyway.placeholders.demo_seed=false", "--payments.simulation-enabled=false",
                "--email.delivery.mode=disabled", "--email.delivery.worker-enabled=false", "--identity.password-recovery.enabled=false",
                // Deliberately unsafe overrides must not expose public health details or docs.
                "--management.endpoint.health.show-details=always", "--management.endpoint.health.show-components=always");
        base = "http://localhost:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class); mapper = context.getBean(ObjectMapper.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); }

    HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        for (int i=0; i<headers.length; i+=2) builder.header(headers[i], headers[i+1]);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void publicHealthIsExactStatusOnlyEvenWithUnsafeConfigOverrides() throws Exception {
        var response = request("GET", "/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("{\"status\":\"UP\"}"));
    }

    @ParameterizedTest @ValueSource(strings = {"/actuator/health/", "/actuator/health/readiness", "/actuator/info", "/actuator/env", "/swagger-ui/index.html"})
    void otherPathsDoNotInheritHealthException(String path) throws Exception {
        assertThat(request("GET", path, null).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "HEAD", "OPTIONS", "PUT", "DELETE"})
    void otherHealthMethodsAreForbidden(String method) throws Exception {
        assertThat(request(method, "/actuator/health", null).statusCode()).isEqualTo(403);
    }

    @Test void gatePrecedesCorsPreflightAndJwt() throws Exception {
        var filters = context.getBean(SecurityFilterChain.class).getFilters();
        int gate=-1, jwt=-1, cors=-1;
        for (int i=0;i<filters.size();i++) {
            if(filters.get(i) instanceof StagingAccessGateFilter)gate=i;
            if(filters.get(i) instanceof JwtAuthFilter)jwt=i;
            if(filters.get(i) instanceof CorsFilter)cors=i;
        }
        assertThat(gate).isGreaterThanOrEqualTo(0).isLessThan(cors).isLessThan(jwt);
        assertThat(request("OPTIONS", "/api/v1/auth/register", null, "Origin", "https://example.invalid",
                "Access-Control-Request-Method", "POST").statusCode()).isEqualTo(403);
    }

    Map<String,Long> counts() {
        var result = new LinkedHashMap<String,Long>();
        for (String table : List.of("identity_accounts", "identity_sessions", "identity_session_tokens", "identity_email_outbox"))
            result.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        return result;
    }

    @Test void rejectedRegistrationLoginAndRefreshHaveNoSideEffects() throws Exception {
        var before=counts();
        String registration=mapper.writeValueAsString(Map.of("name","Test","lastName","Blocked","email",UUID.randomUUID()+"@example.invalid",
                "password","Isolated-Test1!","role","GANADERO"));
        String[][] variants = {{}, {StagingAccessGateFilter.HEADER, ""}, {StagingAccessGateFilter.HEADER, "invalid"},
                {StagingAccessGateFilter.HEADER, StagingAccessGateTests.key()},
                {StagingAccessGateFilter.HEADER, KEY, StagingAccessGateFilter.HEADER, KEY},
                {StagingAccessGateFilter.HEADER, KEY+","+KEY}};
        for (var headers : variants) {
            var response=request("POST","/api/v1/auth/register",registration,headers);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(mapper.readTree(response.body()).path("errorCode").asText()).isEqualTo("STAGING_ACCESS_DENIED");
            assertThat(response.body().contains(KEY)).isFalse();
        }
        for(String path:List.of("/api/v1/auth/login","/api/v1/auth/refresh-token","/api/v1/auth/resend-code"))
            assertThat(request("POST",path,"{}").statusCode()).isEqualTo(403);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void correctKeyPreservesRegistrationRecoveryAndSwaggerContracts() throws Exception {
        String email=UUID.randomUUID()+"@example.invalid";
        String body=mapper.writeValueAsString(Map.of("name","Test","lastName","Allowed","email",email,"password","Isolated-Test1!","role","GANADERO"));
        long jobs=counts().get("identity_email_outbox");
        var response=request("POST","/api/v1/auth/register",body,StagingAccessGateFilter.HEADER,KEY);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(response.body()).path("data").path("emailDelivery").asText()).isEqualTo("UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM identity_accounts WHERE email=?",String.class,email)).isEqualTo("PENDIENTE");
        assertThat(counts().get("identity_email_outbox")).isEqualTo(jobs);
        assertThat(request("POST","/api/v1/auth/password-recovery/request","{\"email\":\"unused@example.invalid\"}",StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(503);
        assertThat(request("GET","/swagger-ui/index.html",null,StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(404);
        assertThat(request("GET","/v3/api-docs",null,StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(404);
    }

    @Test void keyDoesNotReplaceJwtAndJwtDoesNotReplaceKeyOrRoles() throws Exception {
        assertThat(request("GET","/api/v1/account/me",null,StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(401);
        var identity=context.getBean(IdentityService.class);var accounts=context.getBean(AccountRepository.class);
        String email=UUID.randomUUID()+"@example.invalid", password="Isolated-Test1!";
        identity.register(new IdentityService.RegisterCommand("Test","JWT",email,password,Role.GANADERO));
        identity.verifyEmail(email,TestVerificationCodes.code(accounts.findByEmail(email).orElseThrow()));
        String token=identity.login(email,password).accessToken();
        var before=counts();
        assertThat(request("GET","/api/v1/account/me",null,"Authorization","Bearer "+token).statusCode()).isEqualTo(403);
        assertThat(counts()).isEqualTo(before);
        assertThat(request("GET","/api/v1/account/me",null,StagingAccessGateFilter.HEADER,KEY,"Authorization","Bearer "+token).statusCode()).isEqualTo(200);
        assertThat(request("POST","/api/v1/visits","{}",StagingAccessGateFilter.HEADER,KEY,"Authorization","Bearer "+token).statusCode()).isEqualTo(403);
    }

    @Test void validStagingKeyAndJwtCannotActivateOrRenewSimulatedPaymentsForEitherRole() throws Exception {
        for (Role role : List.of(Role.GANADERO, Role.VETERINARIO)) {
            String email=UUID.randomUUID()+"@example.invalid", password="Isolated-"+UUID.randomUUID()+"aA1!";
            String registration=mapper.writeValueAsString(Map.of("name","Test","lastName","Payment gate","email",email,"password",password,"role",role));
            assertThat(request("POST","/api/v1/auth/register",registration,StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(201);
            var account=context.getBean(AccountRepository.class).findByEmail(email).orElseThrow();
            String verification=mapper.writeValueAsString(Map.of("email",email,"code",TestVerificationCodes.code(account)));
            assertThat(request("POST","/api/v1/auth/verify",verification,StagingAccessGateFilter.HEADER,KEY).statusCode()).isEqualTo(200);
            var login=request("POST","/api/v1/auth/login",mapper.writeValueAsString(Map.of("email",email,"password",password)),StagingAccessGateFilter.HEADER,KEY);
            assertThat(login.statusCode()).isEqualTo(200);String token=mapper.readTree(login.body()).path("data").path("accessToken").asText();
            String[] headers={StagingAccessGateFilter.HEADER,KEY,"Authorization","Bearer "+token};
            var plans=request("GET","/api/v1/subscription/plans",null,headers);assertThat(plans.statusCode()).isEqualTo(200);
            UUID plan=jdbc.queryForObject("SELECT id FROM subscriptions_plans WHERE profile=? AND plan_type='PREMIUM'",UUID.class,role.name());
            var before=request("GET","/api/v1/subscription/my-subscription",null,headers);assertThat(before.statusCode()).isEqualTo(200);
            UUID subscription=UUID.fromString(mapper.readTree(before.body()).path("data").path("subscriptionId").asText());
            String ref="fake_ck_"+UUID.randomUUID();
            // Existing pending simulation data must not bypass the environment gate.
            jdbc.update("INSERT INTO subscriptions_payments (id,subscription_id,account_id,plan_id,gateway_ref,amount,currency,status) VALUES (?,?,?,?,?,?,'PEN','PENDING')",
                    UUID.randomUUID(),subscription,account.getId(),plan,ref,role==Role.GANADERO?new java.math.BigDecimal("19.90"):new java.math.BigDecimal("39.90"));
            for (boolean renewal : List.of(false,true)) {
                var response=request("POST","/api/v1/subscription/premium",mapper.writeValueAsString(Map.of("planId",plan,"renewal",renewal)),headers);
                assertThat(response.statusCode()).isEqualTo(503);assertThat(mapper.readTree(response.body()).path("errorCode").asText()).isEqualTo("PAYMENTS_UNAVAILABLE");
            }
            for (String outcome : List.of("APPROVED","DECLINED")) {
                var response=request("POST","/api/v1/subscription/premium/confirm",mapper.writeValueAsString(Map.of("checkoutId",ref,"outcome",outcome)),headers);
                assertThat(response.statusCode()).isEqualTo(503);assertThat(mapper.readTree(response.body()).path("errorCode").asText()).isEqualTo("PAYMENTS_UNAVAILABLE");
            }
            var after=request("GET","/api/v1/subscription/my-subscription",null,headers);assertThat(after.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(after.body()).path("data")).isEqualTo(mapper.readTree(before.body()).path("data"));
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions_payments WHERE gateway_ref=?",String.class,ref)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions_payments WHERE account_id=?",Long.class,account.getId())).isEqualTo(1);
        }
    }
}
