package com.anitec.backend.identity.domain;

/**
 * Outbound port for sending e-mails (verification codes and, through other
 * contexts, vet invitations). Production adapter: Resend. Dev/test adapter:
 * console fallback. A provider acceptance is reported as SENT; any failure as
 * UNCONFIRMED (US14/US25: the record is never rolled back by an e-mail
 * failure, and a sent e-mail never equals a verified account).
 */
public interface EmailSender {

    /**
     * @return true if the provider accepted the message (emailDelivery =
     * "SENT"); false when delivery cannot be confirmed ("UNCONFIRMED").
     */
    boolean send(String to, String subject, String body);
}
