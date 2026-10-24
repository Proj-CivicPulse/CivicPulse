package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.service.photo.ComplaintPhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes uploads whose intent was issued but never confirmed, once they are
 * a day old (docs/photo-upload-contract.md §4). Railway Buckets have no
 * lifecycle rules, so without this every abandoned upload would stay forever.
 * A no-op when photo storage is off.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PhotoOrphanSweepJob {

    private static final long ONE_HOUR_MS = 60 * 60 * 1000L;

    private final ComplaintPhotoService photoService;

    @Scheduled(fixedDelay = ONE_HOUR_MS, initialDelay = ONE_HOUR_MS)
    public void sweep() {
        try {
            int deleted = photoService.sweepOrphans();
            if (deleted > 0) {
                log.info("Photo sweep: deleted {} unconfirmed uploads", deleted);
            }
        } catch (RuntimeException e) {
            // Storage unreachable: harmless, the next run catches up.
            log.warn("Photo sweep failed: {}", e.getMessage());
        }
    }
}
