package com.civicpulse.backend_spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables the async Phase 2 matching trigger and the scheduled reconcile sweep.
 *
 * The trigger to backend-node runs on {@code matchingExecutor} — a small,
 * bounded pool — after the complaint's transaction commits, so the citizen's
 * response is never on the critical path of an embedding call.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("matchingExecutor")
    public ThreadPoolTaskExecutor matchingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Each task is almost entirely I/O wait (HTTP to backend-node, which is
        // itself waiting on the embedding provider), so a handful of threads
        // gives real throughput without competing with the servlet pool. It also
        // caps how many concurrent embedding calls the provider sees.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        // Sized for a burst — a bulk import or scripts/seed-dev-data.mjs submits
        // ~150 complaints far faster than they drain. A queued task is one Long,
        // so headroom here is nearly free, and overflowing it needlessly pushes
        // work onto the (much slower) reconcile sweep.
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("matching-");
        // If the queue is full the submission is rejected rather than run on the
        // caller (the transaction-commit thread) or silently queued forever. A
        // dropped trigger just leaves the complaint PENDING — the reconcile
        // sweep is the backstop for exactly this.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
