package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET /health
 *
 * Reports real service health, not a hardcoded 200: a broken database
 * connection must show up here immediately. Mirrors backend-node's
 * /health exactly, and the response shape is what
 * frontend/src/services/health.service.ts expects:
 * {@code { status: 'ok' | 'error' }}.
 *
 * Public (permitted in SecurityConfig) so uptime probes and the officer
 * dashboard can poll it without credentials.
 *
 * The probe is bounded by {@code app.health-timeout-seconds}: a health
 * check that hangs is worse than one that reports failure.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        if (!databaseReachable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private boolean databaseReachable() {
        // Bounded by app.health-timeout-seconds. A query timeout alone is
        // not enough: when the database is unreachable the call blocks in
        // HikariCP waiting for a connection before any statement is issued.
        // Running it off the request thread means a stuck driver call can
        // never hold the response open past that ceiling — the orphaned
        // task ends on its own once Hikari's connection-timeout fires.
        long timeoutSeconds = appProperties.getHealthTimeoutSeconds();

        CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(() -> {
            try {
                Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                return result != null && result == 1;
            } catch (Exception ex) {
                log.error("Database health check query failed", ex);
                return false;
            }
        });

        try {
            return probe.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            log.error("Database health check timed out after {}s", timeoutSeconds);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.error("Database health check failed", ex);
            return false;
        }
    }
}
