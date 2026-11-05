package com.anitec.backend;

import com.anitec.backend.identity.infrastructure.ResendEmailSender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class EmailTransportTests {
 HttpServer server;ExecutorService executor;ResendEmailSender sender;int status;String response,retry;long sleep;boolean disconnect;
 final List<String> payloads=new CopyOnWriteArrayList<>(),keys=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();
 @BeforeEach void start()throws Exception{status=200;response="{\"id\":\""+UUID.randomUUID()+"\"}";retry=null;sleep=0;disconnect=false;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.createContext("/emails",x->{calls.incrementAndGet();payloads.add(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));keys.add(x.getRequestHeaders().getFirst("Idempotency-Key"));try{if(sleep>0)Thread.sleep(sleep);if(disconnect)return;if(retry!=null)x.getResponseHeaders().add("Retry-After",retry);byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}catch(Exception ignored){}finally{x.close();}});server.start();sender=new ResendEmailSender("synthetic-only","ANITEC <mail@notifications.vitalwatch.space>",new MockEnvironment(),true,1000,1000,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));}
 @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
 ResendEmailSender.Result send(){return sender.deliver("{\"text\":\"private-code-á\"}","anitec-email/opaque-id",Instant.now());}
 @Test void acceptanceRequiresProviderUuidAndStableBytes(){var a=send();var b=send();assertThat(a.kind()).isEqualTo(ResendEmailSender.Kind.ACCEPTED);assertThat(a.providerId()).isNotNull().isEqualTo(b.providerId());assertThat(payloads).containsExactly("{\"text\":\"private-code-á\"}","{\"text\":\"private-code-á\"}");assertThat(keys).containsExactly("anitec-email/opaque-id","anitec-email/opaque-id");}
 @ParameterizedTest @CsvSource({"500,server_error,RETRY","503,server_error,RETRY","429,rate_limit_exceeded,RETRY","429,daily_quota_exceeded,QUOTA","429,monthly_quota_exceeded,QUOTA","409,concurrent_idempotent_requests,RETRY","409,invalid_idempotent_request,FAILED","401,restricted_api_key,CONFIGURATION","403,validation_error,CONFIGURATION","422,validation_error,FAILED","400,invalid_to_address,FAILED"})
 void classifiesProviderFailures(int code,String name,ResendEmailSender.Kind expected){status=code;response="{\"name\":\""+name+"\",\"message\":\"sensitive-recipient-code\"}";assertThat(send().kind()).isEqualTo(expected);assertThat(calls.get()).isEqualTo(1);}
 @Test void timeoutIsUncertainAndHasNoExtraTransportRetries(){sleep=500;sender=new ResendEmailSender("synthetic-only","ANITEC <mail@notifications.vitalwatch.space>",new MockEnvironment(),true,100,100,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));assertThat(send().kind()).isEqualTo(ResendEmailSender.Kind.UNCERTAIN);assertThat(calls.get()).isEqualTo(1);}
 @Test void disconnectIsUncertain(){disconnect=true;assertThat(send().kind()).isEqualTo(ResendEmailSender.Kind.UNCERTAIN);assertThat(calls.get()).isEqualTo(1);}
 @Test void successWithoutIdIsUncertain(){response="{}";assertThat(send().kind()).isEqualTo(ResendEmailSender.Kind.UNCERTAIN);}
 @Test void successWithMalformedIdIsUncertain(){response="{\"id\":\"not-an-id\"}";assertThat(send().kind()).isEqualTo(ResendEmailSender.Kind.UNCERTAIN);}
 @Test void respectsRetryAfterSeconds(){status=429;response="{}";retry="120";assertThat(send().retryAfter()).isEqualTo(Duration.ofSeconds(120));}
 @Test void respectsRetryAfterHttpDate(){status=429;response="{}";Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);retry=DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(90).atZone(ZoneOffset.UTC));var r=sender.deliver("{}","opaque",now);assertThat(r.retryAfter()).isEqualTo(Duration.ofSeconds(90));}
 @Test void malformedBodyIsNeverLogged(){var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ResendEmailSender.class);var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);try{status=403;response="sensitive-recipient-code";send();assertThat(capture.list).isEmpty();}finally{logger.detachAppender(capture);capture.stop();}}
 @Test void malformedApiKeyIsConfigurationEvenWhenProviderUses400(){status=400;response="{\"name\":\"validation_error\",\"message\":\"API key is invalid\"}";assertThat(send().kind()).isEqualTo(ResendEmailSender.Kind.CONFIGURATION);assertThat(calls.get()).isEqualTo(1);}
 @Test void veterinaryInvitationRetainsDirectAcceptanceContract()throws Exception{assertThat(sender.send("invited@example.invalid","Invitation","Existing invitation body")).isTrue();assertThat(payloads).hasSize(1);var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloads.getFirst());assertThat(body.path("text").asText()).isEqualTo("Existing invitation body");assertThat(body.path("to").get(0).asText()).isEqualTo("invited@example.invalid");assertThat(keys.getFirst()).startsWith("anitec-invitation/");}

 @Test void configurationPauseAlsoRespectsRetryAfter(){status=401;response="{}";retry="900";var result=send();assertThat(result.kind()).isEqualTo(ResendEmailSender.Kind.CONFIGURATION);assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(900));}

}
