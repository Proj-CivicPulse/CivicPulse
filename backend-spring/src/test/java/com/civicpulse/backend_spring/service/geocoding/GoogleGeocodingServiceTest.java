package com.civicpulse.backend_spring.service.geocoding;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.GeocodeCache;
import com.civicpulse.backend_spring.repository.GeocodeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cache is what keeps this SKU affordable, so it is the part worth pinning
 * down. Reports cluster hard around one pothole; if every report triggered a
 * lookup, cost would scale with complaint volume instead of with distinct
 * locations.
 *
 * No HTTP is exercised here — RestClient is mocked and never reached on the
 * paths that matter. That is the assertion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleGeocodingServiceTest {

    @Mock
    private RestClient restClient;

    @Mock
    private GeocodeCacheRepository cacheRepository;

    /** Matches the app.geocoding.cache-key-scale default: ~111 m. */
    private static final int SCALE = 3;

    private GoogleGeocodingService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getGeocoding().setProvider("google");
        properties.getGeocoding().setApiKey("test-key");
        service = new GoogleGeocodingService(restClient, cacheRepository, properties);
    }

    @Test
    @DisplayName("a cached address is returned without touching the network")
    void cacheHitSkipsTheCall() {
        when(cacheRepository.findById(any())).thenReturn(Optional.of(
                GeocodeCache.builder()
                        .id(key(12.9260, 77.5940))
                        .address("4th Cross, Jayanagar, Bengaluru")
                        .resolved(true)
                        .build()));

        Optional<String> address = service.reverseGeocode(12.9260, 77.5940);

        assertThat(address).contains("4th Cross, Jayanagar, Bengaluru");
        verifyNoInteractions(restClient);
        verify(cacheRepository, never()).save(any());
    }

    @Test
    @DisplayName("a cached NEGATIVE is also an answer, and is not re-fetched")
    void cachedNegativeSkipsTheCall() {
        // Somewhere with no street address. Asking again next week will not
        // produce one either, and paying to rediscover that is the worst kind
        // of spend.
        when(cacheRepository.findById(any())).thenReturn(Optional.of(
                GeocodeCache.builder()
                        .id(key(12.9260, 77.5940))
                        .address(null)
                        .resolved(false)
                        .build()));

        assertThat(service.reverseGeocode(12.9260, 77.5940)).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("points within ~111 m share a cache key, so a cluster costs one lookup")
    void nearbyPointsShareAKey() {
        // Two reports about the same pothole, ~40 m apart — which is what the
        // real spread looks like when two people drop a pin on one road.
        GeocodeCache.Key first = keyOf(12.92601, 77.59402);
        GeocodeCache.Key second = keyOf(12.92634, 77.59371);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    @DisplayName("points a street apart do NOT share a key")
    void distantPointsDoNotShareAKey() {
        // ~1 km apart: unambiguously different addresses.
        assertThat(keyOf(12.9260, 77.5940)).isNotEqualTo(keyOf(12.9350, 77.5940));
    }

    @Test
    @DisplayName("a blank API key disables the call rather than sending a doomed request")
    void blankKeyShortCircuits() {
        AppProperties properties = new AppProperties();
        properties.getGeocoding().setProvider("google");
        properties.getGeocoding().setApiKey("");
        var unkeyed = new GoogleGeocodingService(restClient, cacheRepository, properties);

        assertThat(unkeyed.reverseGeocode(12.9260, 77.5940)).isEmpty();
        verifyNoInteractions(restClient);
        verifyNoInteractions(cacheRepository);
    }

    @Test
    @DisplayName("scale differences do not defeat the key — 12.9260 and 12.926 are the same place")
    void scaleInsensitiveKey() {
        // BigDecimal.equals is scale-sensitive; using it directly would miss
        // here and re-fetch an address we already paid for.
        GeocodeCache.Key a = new GeocodeCache.Key(new BigDecimal("12.9260"), new BigDecimal("77.5940"));
        GeocodeCache.Key b = new GeocodeCache.Key(new BigDecimal("12.926"), new BigDecimal("77.594"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    private static GeocodeCache.Key key(double lat, double lon) {
        return keyOf(lat, lon);
    }

    /** Delegates to the production rounding so the two cannot drift apart. */
    private static GeocodeCache.Key keyOf(double lat, double lon) {
        return GoogleGeocodingService.keyFor(lat, lon, SCALE);
    }
}
