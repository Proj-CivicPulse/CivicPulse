package com.civicpulse.backend_spring.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class AppConfig {

    private final AppProperties appProperties;

    /**
     * Shared HTTP client for outbound third-party calls — reverse geocoding
     * today. The backend-node matching trigger uses its own bean below, because
     * it wants a much shorter read timeout.
     *
     * TIMEOUTS ARE THE POINT. An untimed client waits as long as the far end
     * takes to answer, so one slow dependency exhausts the request thread pool
     * and takes this service down with it. Both bounds are configurable and
     * deliberately short: every current caller treats failure as "carry on
     * without the enrichment", so waiting longer buys nothing.
     *
     * Built from Spring Framework's own factory rather than a Boot helper —
     * that API has moved packages between Boot versions, and this one has not.
     */
    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(appProperties.getHttpConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(appProperties.getHttpReadTimeoutMs()));

        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Separate client for the Phase 2 trigger to backend-node, with its own
     * (shorter) read timeout. The trigger runs on a background worker after the
     * complaint has committed, so a slow Node must time out fast here rather
     * than hold the worker — Node keeps the PROCESSING claim and finishes on
     * its own. See {@code app.matching.process-timeout-ms}.
     */
    @Bean
    public RestClient matchingRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(appProperties.getHttpConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(appProperties.getMatching().getProcessTimeoutMs()));

        return RestClient.builder()
                .baseUrl(appProperties.getMatching().getNodeBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
