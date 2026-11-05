package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.EmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Single-attempt HTTP transport. Retry policy belongs exclusively to the persistent outbox. */
@Component
public class ResendEmailSender implements EmailSender {
    private static final Logger log=LoggerFactory.getLogger(ResendEmailSender.class);
    private final String apiKey,from;
    private final boolean production,enabled;
    private final RestClient client;
    private final ObjectMapper json=new ObjectMapper();
    public enum Kind { ACCEPTED, RETRY, UNCERTAIN, FAILED, CONFIGURATION, QUOTA }
    public record Result(Kind kind,UUID providerId,Duration retryAfter,String category) {}
    @Autowired public ResendEmailSender(EmailDeliverySettings settings,Environment environment) {
        this(settings.apiKey,settings.from,environment,settings.enabled,settings.connectMillis,settings.responseMillis,URI.create("https://api.resend.com"));
    }
    /** Standalone constructor retained for adapter contract tests; Spring always uses validated settings. */
    public ResendEmailSender(String key,String from,Environment environment) {
        this(key,from,environment,key!=null&&!key.isBlank(),3000,10000,URI.create("https://api.resend.com"));
    }
    public ResendEmailSender(String key,String from,Environment environment,boolean enabled,int connect,int response,URI endpoint) {
        this.apiKey=key;this.from=from;this.enabled=enabled;production=environment.acceptsProfiles(Profiles.of("prod"));
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(connect);factory.setReadTimeout(response);
        client=RestClient.builder().baseUrl(endpoint.toString()).requestFactory(factory).build();
    }
    @Override public boolean passwordRecoveryConfigured() {
        return enabled && apiKey!=null&&!apiKey.isBlank() && from!=null && from.matches("(?i)(?:[a-z0-9._%+-]+@notifications\\.vitalwatch\\.space|[^<>\\r\\n]*<[a-z0-9._%+-]+@notifications\\.vitalwatch\\.space>)");
    }
    @Override public boolean send(String to,String subject,String text) {
        if(!enabled || apiKey==null || apiKey.isBlank()) {
            if(production)log.warn("Email not sent: provider is not configured in production");
            else log.info("Email not sent: provider is not configured in this development/test environment");
            return false;
        }
        try {
            var payload=new LinkedHashMap<String,Object>();payload.put("from",from);payload.put("to",List.of(to));payload.put("subject",subject);payload.put("text",text);
            return deliver(json.writeValueAsString(payload),"anitec-invitation/"+UUID.randomUUID(),Instant.now()).kind()==Kind.ACCEPTED;
        } catch(Exception unavailable){return false;}
    }
    public Result deliver(String payload,String idempotencyKey,Instant now) {
        if(!enabled)return new Result(Kind.CONFIGURATION,null,Duration.ofMinutes(5),"DISABLED");
        try {
            byte[] bytesToSend=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return client.post().uri("/emails").header("Authorization","Bearer "+apiKey)
                .header("Idempotency-Key",idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytesToSend.length)
                // Bypass message-converter debug logging of String bodies; reuse the exact stored UTF-8 bytes.
                .body((org.springframework.http.StreamingHttpOutputMessage.Body) stream->stream.write(bytesToSend))
                .exchange((request,response)-> {
                    int status=response.getStatusCode().value();
                    Duration retry=retryAfter(response.getHeaders().getFirst("Retry-After"),now);
                    byte[] bytes;
                    try {bytes=response.getBody().readNBytes(8193);}
                    catch(java.io.IOException unreadable) {
                        if(status==401 || status==403)return configuration(retry);
                        return new Result(Kind.UNCERTAIN,null,retry,"RESPONSE_UNCERTAIN");
                    }
                    var body=bytes.length>8192?json.createObjectNode():parse(bytes);
                    if(status>=200 && status<300) {
                        try {
                            String id=body.path("id").asText();
                            if(!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException();
                            return new Result(Kind.ACCEPTED,UUID.fromString(id),retry,"ACCEPTED");
                        }
                        catch(IllegalArgumentException invalid){return new Result(Kind.UNCERTAIN,null,retry,"MISSING_PROVIDER_ID");}
                    }
                    String name=body.path("name").asText();
                    if(status==400 && name.equals("validation_error") && body.path("message").asText().equals("API key is invalid"))
                        return configuration(retry);
                    if(status==409 && name.equals("concurrent_idempotent_requests"))return new Result(Kind.RETRY,null,retry,"CONCURRENT_IDEMPOTENCY");
                    if(status==409 && name.equals("invalid_idempotent_request"))return new Result(Kind.FAILED,null,null,"IDEMPOTENCY_MISMATCH");
                    if(status==429 && (name.equals("daily_quota_exceeded") || name.equals("monthly_quota_exceeded")))
                        return new Result(Kind.QUOTA,null,retry==null?Duration.ofHours(24):retry,"PROVIDER_QUOTA");
                    if(status==429)return new Result(Kind.RETRY,null,retry,"RATE_LIMIT");
                    if(status==401 || status==403)return configuration(retry);
                    if(status>=500)return new Result(Kind.RETRY,null,retry,"PROVIDER_5XX");
                    return new Result(Kind.FAILED,null,null,"PROVIDER_REJECTED");
                });
        } catch(Exception unavailable) {
            // HttpURLConnection may expose a streamed 401 as HttpRetryException instead of an error body.
            // Do not retry authentication inside the HTTP client or inspect the sensitive exception message.
            for(Throwable cause=unavailable;cause!=null;cause=cause.getCause()) {
                if(cause instanceof java.net.HttpRetryException auth && (auth.responseCode()==401 || auth.responseCode()==403))
                    return new Result(Kind.CONFIGURATION,null,Duration.ofMinutes(5),"PROVIDER_CONFIGURATION");
            }
            // Exception messages and response bodies can contain recipient, credentials or message content.
            return new Result(Kind.UNCERTAIN,null,null,"NETWORK_UNCERTAIN");
        }
    }
    private static Result configuration(Duration retry){
        Duration minimum=Duration.ofMinutes(5);
        return new Result(Kind.CONFIGURATION,null,retry!=null&&retry.compareTo(minimum)>0?retry:minimum,"PROVIDER_CONFIGURATION");
    }
    private com.fasterxml.jackson.databind.JsonNode parse(byte[] bytes) {
        try{var value=json.readTree(bytes);return value==null?json.createObjectNode():value;}
        catch(Exception invalid){return json.createObjectNode();}
    }
    static Duration retryAfter(String value,Instant now) {
        if(value==null)return null;
        try {long seconds=Long.parseLong(value);return Duration.ofSeconds(Math.max(0,seconds));}
        catch(Exception notSeconds) {
            try {return Duration.ofSeconds(Math.max(0,Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));}
            catch(Exception invalid){return null;}
        }
    }
}
