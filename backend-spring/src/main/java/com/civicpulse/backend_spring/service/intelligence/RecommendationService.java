package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.dashboard.RecommendationSummaryDto;
import com.civicpulse.backend_spring.dto.incident.RecommendationDto;
import com.civicpulse.backend_spring.dto.incident.RecommendationFeedbackRequest;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.repository.RecommendationFeedbackRepository;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules.Action;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules.Decision;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules.Facts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Decision support — docs/roadmap.md, Phase 11. Gathers an incident's facts,
 * applies {@link RecommendationRules}, and logs what officers do with the
 * result (V22). Accepting never changes the incident: the officer still
 * changes status or department themselves.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final IncidentRepository incidentRepository;
    private final ComplaintRepository complaintRepository;
    private final HotspotService hotspotService;
    private final RecommendationFeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public RecommendationDto recommend(Long incidentId) {
        return recommend(incidentId, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    RecommendationDto recommend(Long incidentId, LocalDateTime now) {
        Incident incident = load(incidentId);
        RecommendationDto.Feedback last = lastFeedback(incidentId);
        if (!RecommendationRules.isActive(incident.getStatus())) {
            return new RecommendationDto(Wire.id(incidentId), null, null,
                    "No action to recommend: the incident is "
                            + incident.getStatus().name().toLowerCase(Locale.ROOT) + ".",
                    List.of(), Wire.timestamp(now), last);
        }
        Facts facts = facts(incident, now);
        Decision decision = RecommendationRules.decide(facts);
        return new RecommendationDto(Wire.id(incidentId), Wire.enumValue(decision.action()), decision.ruleId(),
                decision.explanation(), RecommendationRules.describe(facts), Wire.timestamp(now), last);
    }

    @Transactional
    public RecommendationDto feedback(Long incidentId, long userId, RecommendationFeedbackRequest request) {
        return feedback(incidentId, userId, request, LocalDateTime.now());
    }

    @Transactional
    RecommendationDto feedback(Long incidentId, long userId, RecommendationFeedbackRequest request,
                               LocalDateTime now) {
        Incident incident = load(incidentId);
        if (!RecommendationRules.isActive(incident.getStatus())) {
            throw new ValidationException("incident is not active; there is no recommendation to answer");
        }
        // What was recommended is recomputed here, never trusted from the client.
        Decision current = RecommendationRules.decide(facts(incident, now));

        String decision = request.getDecision() == null ? "" : request.getDecision().trim().toLowerCase(Locale.ROOT);
        String chosen = null;
        switch (decision) {
            case "accepted" -> {
                if (request.getChosenAction() != null && !request.getChosenAction().isBlank()) {
                    throw new ValidationException("chosenAction is only for an overridden recommendation");
                }
            }
            case "overridden" -> {
                Action action = Wire.parseEnum(Action.class, request.getChosenAction(), "chosenAction");
                if (action == null) {
                    throw new ValidationException("chosenAction is required when overriding");
                }
                if (action == current.action()) {
                    throw new ValidationException("chosenAction matches the recommendation; send accepted instead");
                }
                chosen = action.name();
            }
            default -> throw new ValidationException("decision must be one of accepted, overridden");
        }

        String note = request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim();
        feedbackRepository.insert(incidentId, current.action().name(), current.ruleId(),
                decision.toUpperCase(Locale.ROOT), chosen, note, userId, now);
        return recommend(incidentId, now);
    }

    /** Accepted vs overridden per recommended action — the Phase 11 metric. */
    @Transactional(readOnly = true)
    public RecommendationSummaryDto summary() {
        List<RecommendationSummaryDto.ActionRow> rows = feedbackRepository.counts().stream()
                .map(c -> new RecommendationSummaryDto.ActionRow(
                        c.recommendedAction().toLowerCase(Locale.ROOT), c.accepted(), c.overridden(),
                        rate(c.overridden(), c.accepted() + c.overridden())))
                .toList();
        long accepted = rows.stream().mapToLong(RecommendationSummaryDto.ActionRow::getAccepted).sum();
        long overridden = rows.stream().mapToLong(RecommendationSummaryDto.ActionRow::getOverridden).sum();
        return new RecommendationSummaryDto(rows, accepted, overridden, rate(overridden, accepted + overridden));
    }

    private Incident load(Long incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
    }

    Facts facts(Incident incident, LocalDateTime now) {
        List<Complaint> members = complaintRepository.findByIncidentIdOrderByReportedAtAsc(incident.getId());
        List<LocalDateTime> reported = members.stream()
                .map(Complaint::getReportedAt).filter(t -> t != null && t.isBefore(now)).toList();
        LocalDateTime first = reported.isEmpty() ? null : reported.getFirst();
        LocalDateTime last = reported.isEmpty() ? null : reported.getLast();
        LocalDateTime recentFrom = now.minusHours(AttentionService.WORSENING_HOURS);
        long recent = reported.stream().filter(t -> !t.isBefore(recentFrom)).count();

        boolean inHotspot = hotspotService
                .hotspots(List.of(incident.getCategory()), List.of(),
                        now.minusDays(HotspotService.DEFAULT_WINDOW_DAYS), now)
                .getHotspots().stream()
                .flatMap(h -> h.getIncidents().stream())
                .anyMatch(m -> m.getId().equals(String.valueOf(incident.getId())));

        return new Facts(incident.getStatus(), incident.getPriorityScore() == null ? 0 : incident.getPriorityScore(),
                daysSince(first, now), daysSince(last, now), recent, inHotspot);
    }

    private RecommendationDto.Feedback lastFeedback(Long incidentId) {
        RecommendationFeedbackRepository.Row row = feedbackRepository.latest(incidentId);
        if (row == null) return null;
        return new RecommendationDto.Feedback(
                row.decision().toLowerCase(Locale.ROOT),
                row.recommendedAction().toLowerCase(Locale.ROOT),
                row.chosenAction() == null ? null : row.chosenAction().toLowerCase(Locale.ROOT),
                row.note(), Wire.id(row.userId()), Wire.timestamp(row.createdAt()));
    }

    private static Long daysSince(LocalDateTime t, LocalDateTime now) {
        return t == null ? null : Math.max(0, Duration.between(t, now).toDays());
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
