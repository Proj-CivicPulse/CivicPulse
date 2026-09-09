package com.civicpulse.backend_spring.service.email;

/**
 * Outbound transactional email.
 *
 * Two implementations, selected by {@code app.email.provider} exactly as the
 * geocoding provider is: {@link ResendEmailSender} for real delivery and
 * {@link LoggingEmailSender} for local work. The default is the logging one, so
 * a fresh clone runs — and the verification and reset flows are fully
 * exercisable — without anyone holding an API key.
 *
 * Implementations MUST NOT throw. A mail provider being down is not a reason for
 * a registration or a password-reset request to fail; the caller has already
 * committed its own work by the time this is reached.
 */
public interface EmailSender {

    /**
     * @param toEmail  recipient address
     * @param subject  subject line
     * @param textBody plain-text body — always sent, and the only body some
     *                 clients will render
     * @param htmlBody HTML body
     */
    void send(String toEmail, String subject, String textBody, String htmlBody);
}
