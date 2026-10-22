package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.MembershipChangeDto;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import com.civicpulse.backend_spring.enums.LifeSafetySource;
import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentMatchLogRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The officer's override for the matcher: split a report off an incident it
 * does not belong to (unlink), or fold one incident into another that is the
 * same problem (merge).
 *
 * <p>Both write through the same rows as every matcher decision — membership,
 * {@code matching_status}, a recompute, an {@code incident_match_log} row
 * (matcher {@code OFFICER}) — so the log stays the one history of how each
 * complaint reached its incident. Moved complaints end {@code MATCHED}, which
 * the reconcile sweep never touches, so a decision a person made is not undone
 * by a machine five minutes later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentMembershipService {

    private static final int MAX_NOTE = 500;

    private final IncidentRepository incidentRepository;
    private final ComplaintRepository complaintRepository;
    private final IncidentMatchLogRepository matchLogRepository;
    private final IncidentAttachmentService attachmentService;

    /**
     * Moves one report off {@code incidentId} into a new incident of its own.
     * Refused for the incident's only report (there is nothing to split from)
     * and on an incident that is no longer active.
     */
    @Transactional
    public MembershipChangeDto unlink(Long incidentId, Long complaintId, long officerId, String note) {
        Incident source = requireActive(incidentId);
        Complaint complaint = complaintRepository.findById(complaintId)
                .filter(c -> c.getIncident() != null && c.getIncident().getId().equals(incidentId))
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found on this incident"));
        if (complaintRepository.countByIncidentId(incidentId) < 2) {
            throw new ValidationException("This is the incident's only report; there is nothing to split it from");
        }

        Incident target = attachmentService.createFrom(complaint);
        move(complaint, target, source, MatchOutcome.UNLINKED, true, officerId, cleanNote(note));

        attachmentService.recompute(target);
        attachmentService.recompute(source);
        log.info("Officer {} unlinked complaint {} from incident {} into new incident {}",
                officerId, complaintId, incidentId, target.getId());
        return new MembershipChangeDto(IncidentDto.from(source), IncidentDto.from(target));
    }

    /**
     * Moves every report of {@code incidentId} into {@code intoIncidentId} and
     * deletes the emptied incident. Both must be active, in the same ward and
     * of the same category: the same rules the matcher works under, so a merge
     * never makes an incident the queue could not have produced.
     */
    @Transactional
    public MembershipChangeDto merge(Long incidentId, Long intoIncidentId, long officerId, String note) {
        if (intoIncidentId == null) {
            throw new ValidationException("intoIncidentId is required");
        }
        if (incidentId.equals(intoIncidentId)) {
            throw new ValidationException("An incident cannot be merged into itself");
        }
        Incident source = requireActive(incidentId);
        Incident target = requireActive(intoIncidentId);
        if (!Objects.equals(source.getWard().getId(), target.getWard().getId())) {
            throw new ValidationException("Only incidents in the same ward can be merged");
        }
        if (!source.getCategory().equals(target.getCategory())) {
            throw new ValidationException("Only incidents of the same category can be merged");
        }

        String reason = cleanNote(note);
        List<Complaint> members = complaintRepository.findByIncidentIdOrderByReportedAtAsc(incidentId);
        for (Complaint complaint : members) {
            move(complaint, target, source, MatchOutcome.MERGED, false, officerId, reason);
        }

        // An officer's "yes, this can kill someone" survives the merge; a
        // text suggestion needs no carrying — recompute re-derives it.
        if (source.isLifeSafety() && source.getLifeSafetySource() == LifeSafetySource.OFFICER
                && target.getLifeSafetySource() != LifeSafetySource.OFFICER) {
            target.setLifeSafety(true);
            target.setLifeSafetySource(LifeSafetySource.OFFICER);
            target.setLifeSafetyReason(source.getLifeSafetyReason());
        }

        attachmentService.recompute(target);
        incidentRepository.delete(source);
        log.info("Officer {} merged incident {} ({} reports) into incident {}",
                officerId, incidentId, members.size(), intoIncidentId);
        return new MembershipChangeDto(null, IncidentDto.from(target));
    }

    private void move(Complaint complaint, Incident target, Incident source, MatchOutcome outcome,
                      boolean created, long officerId, String note) {
        complaint.setIncident(target);
        complaint.setMatchingStatus(MatchingStatus.MATCHED);
        complaint.setMatchedAt(LocalDateTime.now());
        complaintRepository.save(complaint);

        matchLogRepository.save(IncidentMatchLog.builder()
                .complaintId(complaint.getId())
                .decision(outcome)
                .created(created)
                .matcher(Matcher.OFFICER)
                .chosenIncidentId(target.getId())
                .previousIncidentId(source.getId())
                .actorUserId(officerId)
                .note(note)
                .build());
    }

    private Incident requireActive(Long id) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
        if (!RecommendationRules.isActive(incident.getStatus())) {
            throw new ValidationException("Incident " + id + " is not active; reopen it first");
        }
        return incident;
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE) {
            throw new ValidationException("note must be at most " + MAX_NOTE + " characters");
        }
        return trimmed;
    }
}
