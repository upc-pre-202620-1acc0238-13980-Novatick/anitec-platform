package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * EmailSender adapter: uses the Resend HTTP API when {@code resend.api-key}
 * is configured. Otherwise reports UNCONFIRMED without logging recipient or body.
 */
@Component
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final String apiKey;
    private final String from;
    private final boolean production;

    public ResendEmailSender(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:}") String from,
            Environment environment) {
        this.apiKey = apiKey;
        this.from = from;
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Override
    public boolean send(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            if (production) {
                log.warn("Email not sent: provider is not configured in production");
            } else {
                log.info("Email not sent: provider is not configured in this development/test environment");
            }
            return false;
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl("https://api.resend.com")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .build();
            Map<String, Object> payload = Map.of(
                    "from", from,
                    "to", List.of(to),
                    "subject", subject,
                    "text", body);
            ResponseEntity<Void> response = client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            }
            log.warn("Resend rejected an email (HTTP {})", response.getStatusCode().value());
            return false;
        } catch (Exception ex) {
            log.warn("Resend email request failed ({})", ex.getClass().getSimpleName());
            return false;
        }
    }
}
