package com.anitec.backend;

import com.anitec.backend.identity.application.*;
import com.anitec.backend.identity.domain.*;
import com.anitec.backend.identity.infrastructure.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "email.delivery.mode=resend","email.delivery.worker-enabled=false","resend.api-key=synthetic-test-only",
        "resend.from=ANITEC <mail@notifications.vitalwatch.space>","identity.password-recovery.enabled=true",
        "spring.flyway.placeholders.demo_seed=false","payments.simulation-enabled=false","logging.level.root=ERROR"})
class EmailLanguageIntegrationTests {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    static final String encryption=EmailCryptoSettingsTests.key(),hmac=EmailCryptoSettingsTests.key();
    @DynamicPropertySource static void keys(DynamicPropertyRegistry r){r.add("email.delivery.encryption-key",()->encryption);r.add("identity.password-recovery.hmac-key",()->hmac);}
    static class Time extends Clock {final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}}
    static class Queue implements PasswordRecoveryWorkQueue {final java.util.Queue<Runnable> jobs=new ConcurrentLinkedQueue<>();public void submit(Runnable work){jobs.add(work);}void drainOnWorker()throws Exception{try(var executor=Executors.newSingleThreadExecutor()){executor.submit(()->{Runnable r;while((r=jobs.poll())!=null)r.run();}).get(15,TimeUnit.SECONDS);}}}
    static class Provider implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final List<String> payloads=new CopyOnWriteArrayList<>(),keys=new CopyOnWriteArrayList<>();volatile int status=200;
        Provider()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/emails",x->{
            payloads.add(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));keys.add(x.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] body=(status==200?"{\"id\":\""+UUID.randomUUID()+"\"}":"{}").getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,body.length);x.getResponseBody().write(body);x.close();});server.start();}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    @TestConfiguration static class Config {
        @Bean @Primary Time emailTime(){return new Time();}
        @Bean @Primary Queue intake(){return new Queue();}
        @Bean(destroyMethod="close") Provider provider()throws Exception{return new Provider();}
        @Bean @Primary ResendEmailSender localTransport(Provider p,Environment e){return new ResendEmailSender("synthetic-test-only","ANITEC <mail@notifications.vitalwatch.space>",e,true,1000,1000,URI.create("http://127.0.0.1:"+p.server.getAddress().getPort()));}
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailOutboxWorker worker;
    @Autowired JdbcSecurityEmailOutbox outbox;
    @Autowired EmailDeliverySettings settings;
    @Autowired ResendEmailSender sender;
    @Autowired Time clock;
    @Autowired Queue queue;
    @Autowired Provider provider;
    @Autowired IdentityService identity;
    @Autowired PasswordRecoveryRepository recoveries;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment env;
    final HttpClient http=HttpClient.newHttpClient();
    static class User {UUID id;String email,password,token;}
    record Response(int status,JsonNode body){JsonNode data(){return body.path("data");}}
    @BeforeEach void reset(){jdbc.update("DELETE FROM identity_email_outbox");jdbc.update("UPDATE identity_email_delivery_gate SET paused_until='-infinity',error_category=NULL");clock.now.set(Instant.now());queue.jobs.clear();provider.payloads.clear();provider.keys.clear();provider.status=200;}
    Response call(String path,Object body,String language,User auth){try{var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(language!=null)b.header("Accept-Language",language);if(auth!=null)b.header("Authorization","Bearer "+auth.token);
        var r=http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),r.body().isBlank()?json.createObjectNode():json.readTree(r.body()));
    }catch(Exception e){throw new IllegalStateException("Language HTTP request failed",e);}}
    User pending(String role,String language){var u=new User();u.email=UUID.randomUUID()+"@example.invalid";u.password="Isolated-"+UUID.randomUUID()+"aA1!";
        var r=call("/auth/register",Map.of("name","Test","lastName","Languages","email",u.email,"password",u.password,"role",role),language,null);assertThat(r.status()).isEqualTo(201);u.id=UUID.fromString(r.data().path("accountId").asText());return u;}
    User active(String role){var u=pending(role,null);var a=accounts.findById(u.id).orElseThrow();identity.verifyEmail(u.email,TestVerificationCodes.code(a));u.token=identity.login(u.email,u.password).accessToken();worker.cleanup();return u;}
    Map<String,Object> job(User u,String purpose){
        UUID emission=purpose.equals("EMAIL_VERIFICATION")?accounts.findById(u.id).orElseThrow().getVerificationEmissionId():recoveries.find(u.id).orElseThrow().emissionId();
        return jdbc.queryForMap("SELECT * FROM identity_email_outbox WHERE account_id=? AND purpose=? AND emission_id=?",u.id,purpose,emission);
    }
    JsonNode payload(Map<String,Object> row)throws Exception{return json.readTree(new EmailOutboxCipher(settings.encryptionKey()).decrypt((UUID)row.get("id"),(UUID)row.get("emission_id"),(String)row.get("purpose"),new EmailOutboxCipher.Envelope((byte[])row.get("nonce"),(byte[])row.get("ciphertext"))));}
    void language(JsonNode payload,String tag){assertThat(payload.path("html").asText().contains("<html lang=\""+tag+"\">")).isTrue();assertThat(payload.path("text").asText().startsWith(tag.equals("en")?"Your ANITEC code":"Tu código de ANITEC")).isTrue();}
    @ParameterizedTest @CsvSource({"en,en","en-US,en","es-PE,es","fr,es","missing,es","invalid_header,es"})
    void registrationSelectsAndEncryptsLanguageThenDeliversSimulated(String header,String expected)throws Exception{
        var u=pending("GANADERO",header.equals("missing")?null:header);var row=job(u,"EMAIL_VERIFICATION");language(payload(row),expected);
        assertThat(provider.payloads).isEmpty();assertThat(worker.runOne()).isTrue();assertThat(provider.payloads).hasSize(1);language(json.readTree(provider.payloads.get(0)),expected);
        assertThat(jdbc.queryForObject("SELECT state FROM identity_email_outbox WHERE id=?",String.class,row.get("id"))).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM identity_email_outbox WHERE id=?",byte[].class,row.get("id"))).isNull();
    }

    @Test void retryAndNewWorkerPreserveLanguagePayloadKeyProofExpiryAndQuota()throws Exception{
        var u=pending("GANADERO","en");var before=job(u,"EMAIL_VERIFICATION");var account=accounts.findById(u.id).orElseThrow();
        provider.status=503;assertThat(worker.runOne()).isTrue();var retry=job(u,"EMAIL_VERIFICATION");assertThat(retry.get("state")).isEqualTo("RETRY_WAIT");
        // A duplicate call with Spanish must not change a job that already materialized English.
        new TransactionTemplate(manager).executeWithoutResult(t->outbox.verification(accounts.findById(u.id).orElseThrow(),EmailLanguage.ES));
        assertThat(Arrays.equals((byte[])before.get("ciphertext"),(byte[])job(u,"EMAIL_VERIFICATION").get("ciphertext"))).isTrue();
        clock.now.set(((Timestamp)retry.get("next_attempt_at")).toInstant());provider.status=200;
        assertThat(new EmailOutboxWorker(jdbc,settings,sender,clock,manager,env).runOne()).isTrue();assertThat(provider.payloads).hasSize(2);
        assertThat(provider.payloads.get(0).equals(provider.payloads.get(1))).isTrue();assertThat(provider.keys.get(0).equals(provider.keys.get(1))).isTrue();language(json.readTree(provider.payloads.get(1)),"en");
        var after=accounts.findById(u.id).orElseThrow();assertThat(after.getVerificationEmissionId()).isEqualTo(account.getVerificationEmissionId());
        assertThat(Arrays.equals(after.getVerificationVerifier(),account.getVerificationVerifier())).isTrue();assertThat(after.getVerificationResends()).isEqualTo(account.getVerificationResends());
        assertThat(after.getVerificationAttempts()).isEqualTo(account.getVerificationAttempts());assertThat(job(u,"EMAIL_VERIFICATION").get("expires_at")).isEqualTo(before.get("expires_at"));
    }

    @Test void authorizedResendSelectsNewLanguageButPublicEmailOnlyCannotEmit()throws Exception{
        var u=pending("GANADERO","en");var old=job(u,"EMAIL_VERIFICATION");
        assertThat(call("/auth/resend-code",Map.of("email",u.email),"es",null).status()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_email_outbox",Integer.class)).isEqualTo(1);
        assertThat(call("/auth/resend-code",Map.of("email",u.email,"password","wrong"),"es",null).status()).isEqualTo(202);
        jdbc.update("UPDATE identity_accounts SET verification_sent_at=verification_sent_at-interval '61 seconds' WHERE id=?",u.id);
        assertThat(call("/auth/resend-code",Map.of("email",u.email,"password",u.password),"es-PE",null).status()).isEqualTo(202);
        worker.cleanup();var next=job(u,"EMAIL_VERIFICATION");assertThat(next.get("emission_id")).isNotEqualTo(old.get("emission_id"));language(payload(next),"es");
        assertThat(jdbc.queryForObject("SELECT state FROM identity_email_outbox WHERE id=?",String.class,old.get("id"))).isEqualTo("CANCELLED");
        assertThat(worker.runOne()).isTrue();assertThat(provider.payloads).hasSize(1);language(json.readTree(provider.payloads.get(0)),"es");
        var limited=call("/auth/resend-code",Map.of("email",u.email,"password",u.password),"en",null);assertThat(limited.status()).isEqualTo(429);
        assertThat(accounts.findById(u.id).orElseThrow().getVerificationResends()).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"en-US,en","es-PE,es","missing,es"})
    void recoveryCapturesLanguageBeforeQueueAndKeepsItAcrossRetry(String header,String expected)throws Exception{
        var u=active("VETERINARIO");jdbc.update("DELETE FROM identity_email_outbox");
        var response=call("/auth/password-recovery/request",Map.of("email",u.email),header.equals("missing")?null:header,null);assertThat(response.status()).isEqualTo(202);assertThat(response.body().has("data")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_email_outbox",Integer.class)).isZero();
        // A subsequent request selecting the opposite language cannot alter the captured operation.
        String opposite=expected.equals("en")?"es":"en";var another=pending("GANADERO",opposite);language(payload(job(another,"EMAIL_VERIFICATION")),opposite);
        identity.verifyEmail(another.email,TestVerificationCodes.code(accounts.findById(another.id).orElseThrow()));worker.cleanup();
        queue.drainOnWorker();var row=job(u,"PASSWORD_RECOVERY");language(payload(row),expected);var recovery=recoveries.find(u.id).orElseThrow();
        provider.status=503;assertThat(worker.runOne()).isTrue();var waiting=job(u,"PASSWORD_RECOVERY");clock.now.set(((Timestamp)waiting.get("next_attempt_at")).toInstant());provider.status=200;
        assertThat(new EmailOutboxWorker(jdbc,settings,sender,clock,manager,env).runOne()).isTrue();
        assertThat(provider.payloads).hasSize(2);assertThat(provider.payloads.get(0).equals(provider.payloads.get(1))).isTrue();language(json.readTree(provider.payloads.get(1)),expected);
        var after=recoveries.find(u.id).orElseThrow();assertThat(after.emissionId()).isEqualTo(recovery.emissionId());assertThat(after.emissions()).isEqualTo(recovery.emissions());assertThat(after.expiresAt()).isEqualTo(recovery.expiresAt());
    }

    @Test void unknownRecoveryRemainsGenericAndDoesNotCreateMailInEitherLanguage()throws Exception{
        var en=call("/auth/password-recovery/request",Map.of("email",UUID.randomUUID()+"@example.invalid"),"en",null);
        var es=call("/auth/password-recovery/request",Map.of("email",UUID.randomUUID()+"@example.invalid"),"es",null);
        assertThat(en.status()).isEqualTo(202);assertThat(es.status()).isEqualTo(202);assertThat(en.body().has("data")).isFalse();assertThat(en.body().path("message")).isEqualTo(es.body().path("message"));
        queue.drainOnWorker();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_email_outbox",Integer.class)).isZero();assertThat(provider.payloads).isEmpty();
    }

    @ParameterizedTest @CsvSource({"en,en","es-PE,es","missing,es"})
    void invitationsUseSendersOperationLanguageWithoutRecipientPreference(String header,String expected)throws Exception{
        var f=active("GANADERO");var v=active("VETERINARIO");provider.payloads.clear();
        var sent=call("/invitations",Map.of("vetEmail",v.email),header.equals("missing")?null:header,f);assertThat(sent.status()).isEqualTo(201);assertThat(sent.data().path("emailDelivery").asText()).isEqualTo("SENT");
        assertThat(provider.payloads).hasSize(1);var body=json.readTree(provider.payloads.get(0));assertThat(body.path("subject").asText()).isEqualTo(expected.equals("en")?"ANITEC - Veterinary linking invitation":"ANITEC - Invitación de vinculación");
        assertThat(body.path("text").asText().startsWith(expected.equals("en")?"Hello":"Hola")).isTrue();
        assertThat(accounts.findById(v.id).orElseThrow().canSignIn()).isTrue();
    }
}
