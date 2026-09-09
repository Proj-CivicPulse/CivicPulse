package com.civicpulse.backend_spring.service.email;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Delivery via Resend's HTTP API.
 *
 * CALLED FROM SPRING, NOT NODE. Resend publishes a TypeScript SDK, which would
 * argue for putting this in backend-node — but auth belongs to this service
 * (docs/service-boundaries.md), and verification and reset tokens are auth. A
 * round trip through Node would split one flow across two deployables and put a
 * second service on the critical path for issuing a credential. The SDK is a
 * thin wrapper over one JSON POST, so nothing is lost by calling it directly
 * with the RestClient this service already configures with timeouts.
 *
 * FAILURE IS NEVER FATAL, for the same reason as the geocoding client: the
 * caller has already committed its work, and a provider outage must not turn a
 * successful registration into a 500. Errors are logged and swallowed.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
@RequiredArgsConstructor
@Slf4j
public class ResendEmailSender implements EmailSender {

    private static final String ENDPOINT = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final AppProperties appProperties;

    @Override
    public void send(String toEmail, String subject, String textBody, String htmlBody) {
        AppProperties.Email config = appProperties.getEmail();

        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            // Configured for real delivery but given no key. Loud, because
            // unlike a missing geocode this silently breaks account recovery.
            log.error("app.email.provider is 'resend' but app.email.api-key is blank — "
                    + "no message sent to {}", toEmail);
            return;
        }

        try {
            restClient.post()
                    .uri(ENDPOINT)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", config.getFromAddress(),
                            "to", new String[]{toEmail},
                            "subject", subject,
                            "text", textBody,
                            "html", htmlBody))
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Sent \"{}\" to {}", subject, toEmail);

        } catch (RuntimeException ex) {
            // Timeout, 4xx from a bad key or unverified domain, TLS, DNS — all
            // the same to the caller. The address is logged but the body is
            // not: it contains the token.
            log.error("Failed to send \"{}\" to {}: {}", subject, toEmail, ex.getMessage());
        }
    }
}
