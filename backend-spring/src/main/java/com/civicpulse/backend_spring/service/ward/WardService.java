package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.dto.ward.WardSummaryDto;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.repository.WardRepository;
import com.civicpulse.backend_spring.repository.projection.WardOpenCountRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ward reads. Entity-to-DTO mapping happens here, inside the transaction,
 * because open-in-view is off — mapping in a controller would touch detached
 * lazy proxies.
 */
@Service
@RequiredArgsConstructor
public class WardService {

    /** An incident still needs attention in either of these states. */
    private static final List<IncidentStatus> ACTIVE =
            List.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);

    private final WardRepository wardRepository;
    private final IncidentRepository incidentRepository;
    private final WardResolver wardResolver;

    @Transactional(readOnly = true)
    public List<WardDto> list() {
        return wardRepository.findAllByOrderByIdAsc().stream().map(WardDto::from).toList();
    }

    @Transactional(readOnly = true)
    public WardDto getById(Long id) {
        return WardDto.from(requireWard(id));
    }

    @Transactional(readOnly = true)
    public Ward requireWard(Long id) {
        return wardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
    }

    @Transactional(readOnly = true)
    public WardDto resolve(double latitude, double longitude) {
        return wardResolver.resolve(latitude, longitude)
                .map(WardDto::from)
                // A point outside every ward is a real answer, not a failure.
                // The submit form reads this 404 as "fall back to the picker"
                // rather than letting us file the report into whichever ward
                // happened to be least far away.
                .orElseThrow(() -> new ResourceNotFoundException("No ward covers that location"));
    }

    /**
     * Ward names with open-incident counts. Counts, never rows.
     *
     * One grouped query rather than a count per ward. Wards with no open
     * incidents are absent from the grouped result, so they are defaulted to
     * zero here — a ward with nothing open still belongs in the list.
     */
    @Transactional(readOnly = true)
    public List<WardSummaryDto> summary() {
        Map<Long, Long> openByWard = new HashMap<>();
        for (WardOpenCountRow row : incidentRepository.countOpenGroupedByWard(ACTIVE)) {
            openByWard.put(row.getWardId(), row.getOpenCount());
        }

        return wardRepository.findAllByOrderByIdAsc().stream()
                .map(ward -> new WardSummaryDto(
                        String.valueOf(ward.getId()),
                        ward.getName(),
                        openByWard.getOrDefault(ward.getId(), 0L)))
                .toList();
    }
}
