package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.incident.GroupingDto;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentMatchLogRepository;
import com.civicpulse.backend_spring.util.GeoDistance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Why are these grouped" — each report's latest decision from
 * {@code incident_match_log}, told as an officer would: how far apart, how
 * alike the wording, who decided. Similarity is put in words, never as a
 * number; a cosine score is a fact about the model, not about the street.
 */
@Service
@RequiredArgsConstructor
public class GroupingExplainer {

    /** Wording bands above the join threshold. */
    static final double NEARLY_IDENTICAL = 0.95;
    static final double VERY_SIMILAR = 0.90;

    private final IncidentService incidentService;
    private final ComplaintRepository complaintRepository;
    private final IncidentMatchLogRepository matchLogRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public GroupingDto explain(Long incidentId) {
        incidentService.requireIncident(incidentId);
        List<Complaint> members = complaintRepository.findByIncidentIdOrderByReportedAtAsc(incidentId);
        List<Long> ids = members.stream().map(Complaint::getId).toList();

        Map<Long, IncidentMatchLog> latest = new HashMap<>();
        for (IncidentMatchLog row : ids.isEmpty() ? List.<IncidentMatchLog>of()
                : matchLogRepository.findByComplaintIdInOrderByIdDesc(ids)) {
            latest.putIfAbsent(row.getComplaintId(), row);
        }

        List<Long> siblingIds = latest.values().stream()
                .map(IncidentMatchLog::getTopSiblingComplaintId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Complaint> siblings = siblingIds.isEmpty() ? Map.of()
                : complaintRepository.findAllById(siblingIds).stream()
                        .collect(Collectors.toMap(Complaint::getId, Function.identity()));

        double naiveKm = appProperties.getIncidentGrouping().getMaxDistanceKm();
        List<GroupingDto.Member> out = members.stream()
                .map(c -> member(c, latest.get(c.getId()), siblings, naiveKm))
                .toList();
        return new GroupingDto(Wire.id(incidentId), out);
    }

    static GroupingDto.Member member(Complaint c, IncidentMatchLog row, Map<Long, Complaint> siblings,
                                     double naiveKm) {
        if (row == null) {
            return new GroupingDto.Member(Wire.id(c.getId()), c.getReferenceNo(), "unknown",
                    "No grouping decision is on record for this report.", null, null, null);
        }
        Complaint sibling = row.getTopSiblingComplaintId() == null ? null : siblings.get(row.getTopSiblingComplaintId());
        Integer distance = distance(c, sibling);
        String siblingRef = sibling == null ? null : sibling.getReferenceNo();
        String kind;
        String text;

        if (row.getMatcher() == Matcher.OFFICER) {
            String note = row.getNote() == null ? "" : " Reason given: " + row.getNote();
            if (row.getDecision() == MatchOutcome.UNLINKED) {
                kind = "split";
                text = "An officer split this report off incident " + row.getPreviousIncidentId()
                        + " as a different problem." + note;
            } else {
                kind = "merged";
                text = "An officer merged incident " + row.getPreviousIncidentId()
                        + " into this one as the same problem." + note;
            }
            return new GroupingDto.Member(Wire.id(c.getId()), c.getReferenceNo(), kind, text, null, null,
                    Wire.timestamp(row.getCreatedAt()));
        }

        if (row.getMatcher() == Matcher.NAIVE) {
            String km = String.format(Locale.ROOT, "%.1f", naiveKm);
            kind = row.isCreated() ? "started" : "backup_rule";
            text = row.isCreated()
                    ? "Opened this incident under the backup rule (text matching was unavailable): no open report "
                            + "of this category within " + km + " km."
                    : "Grouped by the backup rule while text matching was unavailable: same ward and category, "
                            + "within " + km + " km. It is re-checked when matching is back.";
            return new GroupingDto.Member(Wire.id(c.getId()), c.getReferenceNo(), kind, text, null, null,
                    Wire.timestamp(row.getCreatedAt()));
        }

        // Semantic: a first decision or a re-check after the backup rule.
        String prefix = row.getDecision() == MatchOutcome.RECONCILED ? "Re-checked after the backup rule: " : "";
        Double sim = row.getTopSimilarity();
        Double threshold = row.getThreshold();
        boolean cleared = sim != null && threshold != null && sim >= threshold;

        if (row.isCreated()) {
            kind = row.getDecision() == MatchOutcome.RECONCILED ? "rechecked" : "started";
            if (row.getCandidateCount() == null || row.getCandidateCount() == 0) {
                text = prefix + "Opened this incident: there was no other open report of this category in the ward.";
            } else if (cleared && siblingRef != null) {
                text = prefix + "Opened this incident: report " + siblingRef + " reads alike but is "
                        + metres(distance) + " away, too far to be the same fault.";
            } else {
                text = prefix + "Opened this incident: no open report in the ward described the same problem.";
            }
            return new GroupingDto.Member(Wire.id(c.getId()), c.getReferenceNo(), kind, text,
                    cleared ? siblingRef : null, cleared ? distance : null, Wire.timestamp(row.getCreatedAt()));
        }

        kind = row.getDecision() == MatchOutcome.RECONCILED ? "rechecked" : "joined";
        String wording = sim == null ? "a similar description"
                : sim >= NEARLY_IDENTICAL ? "nearly the same wording"
                : sim >= VERY_SIMILAR ? "a very similar description"
                : "a similar description of the same kind of fault";
        text = prefix + (siblingRef == null
                ? "Grouped here: " + wording + " as a report already in this incident."
                : "Grouped with report " + siblingRef + ": " + wording
                        + (distance == null ? "." : ", " + metres(distance) + " away."));
        return new GroupingDto.Member(Wire.id(c.getId()), c.getReferenceNo(), kind, text, siblingRef, distance,
                Wire.timestamp(row.getCreatedAt()));
    }

    private static Integer distance(Complaint a, Complaint b) {
        if (b == null || a.getLatitude() == null || a.getLongitude() == null
                || b.getLatitude() == null || b.getLongitude() == null) {
            return null;
        }
        return (int) Math.round(GeoDistance.metresBetween(a.getLatitude(), a.getLongitude(),
                b.getLatitude(), b.getLongitude()));
    }

    /** "40 m", "1.2 km". Rounded to what a person would say. */
    static String metres(Integer m) {
        if (m == null) return "some distance";
        if (m < 1000) return (m < 10 ? m : Math.round(m / 10.0) * 10) + " m";
        return String.format(Locale.ROOT, "%.1f km", m / 1000.0);
    }
}
