package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.exception.CopilotException;
import com.civicpulse.backend_spring.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Spring -> Node for the Copilot: {@code POST /internal/copilot/answer}.
 *
 * Node makes the model call; this client sends the question and the packet and
 * turns every failure into one of the three Copilot states the officer sees —
 * not configured, busy, or failed — instead of a generic 500.
 */
@Component
@Slf4j
public class NodeCopilotClient {

    /** Node's reply, before Spring checks the citations. */
    public record NodeAnswer(boolean answerable, String answer, List<String> citations, String model, boolean refused) {
    }

    /** Node's error envelope, read only for its code. */
    record NodeError(Detail error) {
        record Detail(String code, String message) {
        }
    }

    private final RestClient restClient;
    private final String internalToken;

    public NodeCopilotClient(@Qualifier("copilotRestClient") RestClient copilotRestClient, AppProperties appProperties) {
        this.restClient = copilotRestClient;
        this.internalToken = appProperties.getInternalToken();
    }

    public NodeAnswer answer(String question, Map<String, Object> packet) {
        try {
            NodeAnswer answer = restClient.post()
                    .uri("/internal/copilot/answer")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("question", question, "packet", packet))
                    .retrieve()
                    .body(NodeAnswer.class);
            if (answer == null || answer.answer() == null) {
                throw CopilotException.upstream("The Copilot returned an empty answer");
            }
            return answer;
        } catch (RestClientResponseException ex) {
            String code = errorCode(ex);
            if (ErrorCode.COPILOT_UNAVAILABLE.equals(code)) throw CopilotException.unavailable();
            if (ErrorCode.COPILOT_BUSY.equals(code)) throw CopilotException.busy();
            log.warn("backend-node answered the Copilot with {} {}", ex.getStatusCode(), code);
            throw CopilotException.upstream("The Copilot could not answer right now");
        } catch (ResourceAccessException ex) {
            log.warn("backend-node unreachable for the Copilot: {}", ex.getMessage());
            throw CopilotException.upstream("The Copilot service could not be reached");
        }
    }

    private static String errorCode(RestClientResponseException ex) {
        try {
            NodeError body = ex.getResponseBodyAs(NodeError.class);
            return body == null || body.error() == null ? null : body.error().code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
