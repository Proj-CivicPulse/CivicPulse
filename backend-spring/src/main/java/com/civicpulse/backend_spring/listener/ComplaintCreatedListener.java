package com.civicpulse.backend_spring.listener;

import com.civicpulse.backend_spring.event.ComplaintCreatedEvent;
import com.civicpulse.backend_spring.service.matching.ComplaintMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges a committed complaint to the async matching trigger.
 *
 * AFTER_COMMIT, not the plain publish, so matching never runs for a submission
 * that later rolled back. {@link ComplaintMatchingService#trigger} is
 * {@code @Async}, so this returns immediately and the citizen's response is
 * already on its way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplaintCreatedListener {

    private final ComplaintMatchingService matchingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComplaintCreated(ComplaintCreatedEvent event) {
        try {
            matchingService.trigger(event.complaintId());
        } catch (RuntimeException ex) {
            // e.g. the matching executor's bounded queue is full (AbortPolicy).
            // The complaint is committed and left PENDING; the reconcile sweep
            // is the backstop for a dropped trigger.
            log.warn("Could not dispatch matching for complaint {}: {}",
                    event.complaintId(), ex.toString());
        }
    }
}
