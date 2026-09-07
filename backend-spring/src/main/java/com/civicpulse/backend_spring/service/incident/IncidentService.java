package com.civicpulse.backend_spring.service.incident;

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
import com.civicpulse.backend_spring.repository.spec.IncidentSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final ComplaintRepository complaintRepository;
    private final DepartmentRepository departmentRepository;

    /**
     * Default ordering is the queue's whole purpose: most urgent first, with
     * recency breaking ties between equal scores.
     */
    @Transactional(readOnly = true)
    public List<IncidentDto> list(Long wardId, String category, IncidentStatus status, Double minPriority) {
        if (minPriority != null && (minPriority < 0 || minPriority > 10)) {
            throw new ValidationException("minPriority must be between 0 and 10");
        }

        Specification<Incident> spec = Specification.allOf(
                IncidentSpecifications.hasWard(wardId),
                IncidentSpecifications.hasCategory(category),
                IncidentSpecifications.hasStatus(status),
                IncidentSpecifications.minPriority(minPriority));

        Sort sort = Sort.by(Sort.Direction.DESC, "priorityScore")
                .and(Sort.by(Sort.Direction.DESC, "createdAt"));

        return incidentRepository.findAll(spec, sort).stream().map(IncidentDto::from).toList();
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
        return complaintRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream()
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
            incident.setStatus(Wire.parseEnum(IncidentStatus.class, request.getStatus(), "status"));
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
