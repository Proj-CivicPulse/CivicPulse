package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.dto.copilot.CopilotAnswerDto;
import com.civicpulse.backend_spring.dto.copilot.CopilotQueryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The officer Copilot (docs/roadmap.md, Phase 12): rate limit, build
 * the grounding packet, ask backend-node, then CHECK the answer before the
 * officer sees it.
 *
 * <p>The check is the point. The model was told to cite only packet ids; this
 * is where that is enforced rather than hoped for. A citation Spring did not
 * put in the packet is dropped and counted, and an answer left with no valid
 * citation is flagged {@code ungrounded} so the UI can say so instead of
 * presenting it as fact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CopilotService {

    private final CopilotRateLimiter rateLimiter;
    private final CopilotPacketBuilder packetBuilder;
    private final NodeCopilotClient nodeClient;

    public CopilotAnswerDto ask(Long userId, CopilotQueryRequest request) {
        rateLimiter.acquire(userId);

        CopilotPacketBuilder.Packet packet = packetBuilder.build(request);
        NodeCopilotClient.NodeAnswer answer = nodeClient.answer(request.getQuestion().trim(), packet.body());

        return checked(answer, packet);
    }

    /** Keeps only citations the packet actually contained, in the model's order, once each. */
    static CopilotAnswerDto checked(NodeCopilotClient.NodeAnswer answer, CopilotPacketBuilder.Packet packet) {
        Map<String, CopilotAnswerDto.Source> index = packet.sources();
        List<CopilotAnswerDto.Source> sources = new ArrayList<>();
        int dropped = 0;
        for (String id : new LinkedHashSet<>(answer.citations() == null ? List.of() : answer.citations())) {
            CopilotAnswerDto.Source source = index.get(id == null ? "" : id.trim());
            if (source == null) {
                dropped++;
            } else {
                sources.add(source);
            }
        }
        boolean ungrounded = answer.answerable() && !answer.refused() && sources.isEmpty();
        if (dropped > 0 || ungrounded) {
            log.warn("copilot answer citations: {} valid, {} dropped, ungrounded={}",
                    sources.size(), dropped, ungrounded);
        }
        return new CopilotAnswerDto(
                answer.answer(),
                answer.answerable(),
                answer.refused(),
                ungrounded,
                dropped,
                sources,
                packet.scope(),
                answer.model(),
                Instant.now().toString());
    }
}
