package com.civicpulse.backend_spring.service.email;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds and dispatches the two account emails.
 *
 * SENT ASYNCHRONOUSLY, on the shared bounded pool. The caller has already
 * committed its work — the account exists, the reset token is stored — and a
 * provider that is slow or down must not hold the HTTP response open or turn a
 * successful registration into a 500. It also keeps the password-reset endpoint
 * from leaking, through response time, whether an address was found: the send
 * happens after the controller has answered either way.
 *
 * Links point at the FRONTEND ({@code app.email.link-base-url}), because a human
 * clicks them and needs to land on a page, not a JSON endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthEmailComposer {

    private final EmailSender emailSender;
    private final AppProperties appProperties;

    @Async("matchingExecutor")
    public void sendVerification(String toEmail, String name, String token) {
        String link = link("/verify-email", token);
        long hours = appProperties.getEmail().getVerificationTtlHours();

        send(toEmail, "Confirm your CivicPulse email address", """
                Hi %s,

                Confirm this address to finish setting up your CivicPulse account:

                %s

                The link works for %d hours. If you did not create an account, you can
                ignore this message — nothing will happen until the link is used.
                """.formatted(name, link, hours));
    }

    @Async("matchingExecutor")
    public void sendPasswordReset(String toEmail, String name, String token) {
        String link = link("/reset-password", token);
        long minutes = appProperties.getEmail().getResetTtlMinutes();

        send(toEmail, "Reset your CivicPulse password", """
                Hi %s,

                Someone asked to reset the password for this address. Choose a new one here:

                %s

                The link works for %d minutes and can only be used once.

                If this was not you, no action is needed — your password has not changed,
                and this link will expire on its own. Requesting another reset immediately
                invalidates this one.
                """.formatted(name, link, minutes));
    }

    private void send(String toEmail, String subject, String textBody) {
        // A minimal HTML part: enough that the link is clickable in a client
        // that prefers HTML, with no images, tracking, or external CSS —
        // anything remote in a security email is a phishing tell.
        String htmlBody = "<pre style=\"font:14px/1.5 system-ui,sans-serif;white-space:pre-wrap\">"
                + escapeHtml(textBody) + "</pre>";
        emailSender.send(toEmail, subject, textBody, htmlBody);
    }

    private String link(String path, String token) {
        String base = appProperties.getEmail().getLinkBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // The token is URL-safe Base64, but encoding it is still correct: a
        // future change to the alphabet must not silently produce broken links.
        return base + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
