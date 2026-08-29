package com.civicpulse.backend_spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * Shared HTTP client for outbound calls to backend-node (Phase 2:
     * POST /complaints/{id}/process). Unused until that integration lands —
     * kept here so the wiring exists in one place when it does.
     *
     * To-Do (Phase 2): set connect/read timeouts before this makes a real
     * call. An untimed client will hang a request thread if Node is down —
     * see the open "retry/timeout behavior" question in docs/api-contract.md.
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }
}