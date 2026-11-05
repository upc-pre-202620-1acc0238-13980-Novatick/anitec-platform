package com.anitec.backend.identity.domain;

/**
 * Outbound port for sending e-mails (verification codes and, through other
 * contexts, vet invitations). Production adapter: Resend. Dev/test adapter:
 * unavailable provider (no body logging). A provider acceptance is reported as SENT; any failure as
 * UNCONFIRMED (US14/US25: the record is never rolled back by an e-mail
 * failure, and a sent e-mail never equals a verified account).
 */
public interface EmailSender {

    /**
     * @return true if the provider accepted the message (emailDelivery =
     * "SENT"); false when delivery cannot be confirmed ("UNCONFIRMED").
     */
    boolean send(String to, String subject, String body);
    /** Readiness is opt-in; console/unconfigured adapters cannot enable password recovery. */
    default boolean passwordRecoveryConfigured() { return false; }

    /** One call per committed logical issuance; identifier is available for future provider idempotency. */
    default boolean sendPasswordRecovery(java.util.UUID emissionId, String to, String subject, String body) {
        return send(to, subject, body);
    }
}
