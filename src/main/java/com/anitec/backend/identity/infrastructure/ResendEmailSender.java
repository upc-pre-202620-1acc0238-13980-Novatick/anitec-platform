package com.anitec.backend.identity.infrastructure;

import com.anitec.backend.identity.domain.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * EmailSender adapter: uses the Resend HTTP API when {@code resend.api-key}
 * is configured; otherwise falls back to a console logger (dev/test) so the
 * application always boots (decision #6).
 */
@Component
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final String apiKey;
    private final String from;

    public ResendEmailSender(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:}") String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public boolean send(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[EMAIL CONSOLE FALLBACK] to={} | subject={} | {}", to, subject,
                    body.replace('\n', ' '));
            return true;
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
            log.warn("Resend rejected the message to {} (HTTP {})", to, response.getStatusCode().value());
            return false;
        } catch (Exception ex) {
            log.warn("Resend call failed for {}: {}", to, ex.getMessage());
            return false;
        }
    }
}
