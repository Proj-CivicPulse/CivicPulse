package com.civicpulse.backend_spring.service.matching;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;

/**
 * The Spring -> Node half of the Phase 2 trigger: {@code POST /complaints/{id}/process}.
 *
 * Node does the embedding, the similarity search, and the callback to
 * {@code /internal/incidents/attach}. This client only kicks it off and
 * classifies failure into two kinds the circuit breaker treats differently
 * (see {@link NodeConnectionException} / {@link NodeSlowException}).
 */
@Component
@Slf4j
public class NodeMatchingClient {

    private final RestClient restClient;
    private final String internalToken;

    public NodeMatchingClient(
            @Qualifier("matchingRestClient") RestClient matchingRestClient,
            AppProperties appProperties) {
        this.restClient = matchingRestClient;
        this.internalToken = appProperties.getInternalToken();
    }

    /**
     * @param force forwarded as {@code ?force=true} — lets Node re-claim a
     *              MATCHED complaint or a stale PROCESSING one (used by the
     *              reconcile sweep)
     */
    public void process(Long complaintId, boolean force) {
        try {
            restClient.post()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/complaints/{id}/process");
                        if (force) {
                            uriBuilder.queryParam("force", "true");
                        }
                        return uriBuilder.build(complaintId);
                    })
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new NodeSlowException(
                        "backend-node timed out processing complaint " + complaintId, ex);
            }
            throw new NodeConnectionException(
                    "backend-node unreachable for complaint " + complaintId, ex);
        } catch (RestClientResponseException ex) {
            // 4xx (misconfig, e.g. a rejected token) or 5xx (a Node-side fault).
            // Either way the decision did not land — treat it as a real failure.
            throw new NodeConnectionException(
                    "backend-node responded " + ex.getStatusCode()
                            + " for complaint " + complaintId, ex);
        }
    }
}
