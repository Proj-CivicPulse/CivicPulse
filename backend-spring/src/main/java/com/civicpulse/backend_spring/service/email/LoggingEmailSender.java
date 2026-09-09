package com.civicpulse.backend_spring.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The default sender: writes the message to the log instead of delivering it.
 *
 * This is what makes the email flows testable with no account and no key — the
 * verification and reset links appear in the console, so a developer can copy
 * one and complete the flow end to end. It is also why {@code app.email.provider}
 * defaults to "log": a fresh clone must run without a billing relationship, the
 * same principle as {@code app.geocoding.provider=none}.
 *
 * The link is logged in full ON PURPOSE, which is safe only because this
 * implementation never runs in production — switching the provider to "resend"
 * swaps this bean out entirely. Logging a live reset link on a real deployment
 * would put account takeover in the log aggregator.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String toEmail, String subject, String textBody, String htmlBody) {
        log.info("""

                        ┌─ EMAIL (not sent — app.email.provider=log) ─────────────
                        │ To:      {}
                        │ Subject: {}
                        ├─────────────────────────────────────────────────────────
                        {}
                        └─────────────────────────────────────────────────────────""",
                toEmail, subject, textBody);
    }
}
