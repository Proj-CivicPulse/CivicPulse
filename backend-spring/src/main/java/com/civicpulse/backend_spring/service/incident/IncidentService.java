package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.entity.Department;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.repository.OffsetPageRequest;
import com.civicpulse.backend_spring.repository.spec.IncidentSpecifications;
import com.civicpulse.backend_spring.service.category.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final ComplaintRepository complaintRepository;
    private final DepartmentRepository departmentRepository;
    private final CategoryService categoryService;

    /**
     * One page of the incident queue. Default ordering is the queue's whole
     * purpose: most urgent first, then most recently reported, then id.
     */
    @Transactional(readOnly = true)
    public PageResponse<IncidentDto> search(IncidentQuery query) {
        // Canonicalised so ?category=Garbage and ?category=solid%20waste both
        // select the rows stored as 'garbage'. Unresolvable values pass through
        // untouched — see CategoryService.canonicaliseFilter.
        List<String> categories = query.categories().stream()
                .map(categoryService::canonicaliseFilter)
                .distinct()
                .toList();

        Specification<Incident> spec = Specification.allOf(
                IncidentSpecifications.hasWardIn(query.wardIds()),
                IncidentSpecifications.hasCategoryIn(categories),
                IncidentSpecifications.hasStatusIn(query.statuses()),
                IncidentSpecifications.hasBandIn(query.bands()),
                IncidentSpecifications.minPriority(query.minPriority()),
                IncidentSpecifications.hasMemberReportedBetween(query.from(), query.to()),
                IncidentSpecifications.matchesText(query.text()));

        Page<Incident> page = incidentRepository.findAll(
                spec, new OffsetPageRequest(query.offset(), query.limit(), query.sort()));

        return new PageResponse<>(
                page.getContent().stream().map(IncidentDto::from).toList(),
                page.getTotalElements(),
                query.limit(),
                query.offset());
    }

    @Transactional(readOnly = true)
    public IncidentDto getById(Long id) {
        return IncidentDto.from(requireIncident(id));
    }

    @Transactional(readOnly = true)
    public List<ComplaintDto> getComplaints(Long id) {
        // Check the incident exists first: an empty list would otherwise be
        // ambiguous between "no reports" and "no such incident".
        requireIncident(id);
        return complaintRepository.findByIncidentIdOrderByReportedAtAsc(id).stream()
                .map(ComplaintDto::from)
                .toList();
    }

    @Transactional
    public IncidentDto update(Long id, UpdateIncidentRequest request) {
        boolean hasAny = notBlank(request.getStatus())
                || notBlank(request.getDepartmentId())
                || notBlank(request.getTitle())
                || notBlank(request.getSummary());

        if (!hasAny) {
            throw new ValidationException(
                    "At least one of status, departmentId, title, summary is required");
        }

        Incident incident = requireIncident(id);

        if (notBlank(request.getStatus())) {
            applyStatus(
                    incident,
                    Wire.parseEnum(IncidentStatus.class, request.getStatus(), "status"));
        }
        if (notBlank(request.getTitle())) {
            incident.setTitle(request.getTitle());
        }
        if (notBlank(request.getSummary())) {
            incident.setSummary(request.getSummary());
        }
        if (notBlank(request.getDepartmentId())) {
            Department department = departmentRepository
                    .findById(parseId(request.getDepartmentId()))
                    .orElseThrow(() -> new ValidationException(
                            "departmentId does not match a known department"));
            incident.setDepartment(department);
        }

        // Priority is deliberately NOT recomputed here. Status is an officer's
        // judgement about handling, not an input to the model — only membership
        // changes move a score (docs/service-boundaries.md decision 1).
        return IncidentDto.from(incidentRepository.save(incident));
    }

    /**
     * The only writer of {@code resolvedAt}, so the column cannot disagree with
     * the status it describes.
     *
     * <p>Re-sending {@code RESOLVED} on an already-resolved incident is
     * idempotent: an officer correcting the summary of a resolved incident must
     * not move its resolution time, which is what re-stamping on every write
     * would do. Leaving {@code RESOLVED} for ANY status clears the stamp —
     * reopening, because a resolution time on an open incident is worse than
     * none (see {@link Incident#getResolvedAt}), and {@code CLOSED}, because
     * closed means terminated without a fix ({@link IncidentStatus}).
     */
    private void applyStatus(Incident incident, IncidentStatus next) {
        boolean wasResolved = incident.getStatus() == IncidentStatus.RESOLVED;
        boolean isResolved = next == IncidentStatus.RESOLVED;

        if (isResolved && !wasResolved) {
            incident.setResolvedAt(LocalDateTime.now());
        } else if (!isResolved && wasResolved) {
            incident.setResolvedAt(null);
        }

        incident.setStatus(next);
    }

    @Transactional(readOnly = true)
    public Incident requireIncident(Long id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException("departmentId must be a valid id");
        }
    }
}
