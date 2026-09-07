package com.civicpulse.backend_spring.service.geocoding;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.GeocodeCache;
import com.civicpulse.backend_spring.repository.GeocodeCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reverse geocoding via the Google Geocoding API.
 *
 * CALLED FROM THE SERVER, DELIBERATELY. The browser never sees this. That keeps
 * the API key secret and IP-restrictable rather than a public referrer-scoped
 * key, and it leaves the frontend's strict Content-Security-Policy
 * (default-src 'self') untouched — a browser-side Maps call would have required
 * opening script-src and connect-src to googleapis.com.
 *
 * COST SHAPE. Map tiles bill per page view, which is unbounded and grows every
 * time someone hits refresh. Geocoding bills per NEW LOCATION, which is bounded
 * by real civic activity and cacheable forever, because the street outside a
 * pothole does not get renamed. That asymmetry is why this is the paid call the
 * project takes on and Google Maps tiles are not.
 *
 * FAILURE IS NEVER FATAL. Every error path returns empty. A resident submitting
 * a report must not be blocked because Google is slow, over quota, or down.
 */
@Service
@ConditionalOnProperty(name = "app.geocoding.provider", havingValue = "google")
@RequiredArgsConstructor
@Slf4j
public class GoogleGeocodingService implements GeocodingService {

    private static final String ENDPOINT = "https://maps.googleapis.com/maps/api/geocode/json";

    private final RestClient restClient;
    private final GeocodeCacheRepository cacheRepository;
    private final AppProperties appProperties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> reverseGeocode(double latitude, double longitude) {
        String apiKey = appProperties.getGeocoding().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // Configured as the provider but given no key. Warn once per call
            // rather than failing: the report itself is still fine.
            log.warn("Geocoding provider is 'google' but app.geocoding.api-key is blank");
            return Optional.empty();
        }

        GeocodeCache.Key key = keyFor(latitude, longitude, appProperties.getGeocoding().getCacheKeyScale());

        Optional<GeocodeCache> cached = cacheRepository.findById(key);
        if (cached.isPresent()) {
            GeocodeCache hit = cached.get();
            // A cached negative counts as an answer. Re-asking would cost money
            // to rediscover that a point in a field has no street address.
            return hit.isResolved() ? Optional.ofNullable(hit.getAddress()) : Optional.empty();
        }

        Optional<String> address = fetch(latitude, longitude, apiKey);
        store(key, address);
        return address;
    }

    private Optional<String> fetch(double latitude, double longitude, String apiKey) {
        try {
            String url = UriComponentsBuilder.fromUriString(ENDPOINT)
                    .queryParam("latlng", latitude + "," + longitude)
                    .queryParam("key", apiKey)
                    // Street address rather than a plus-code or a whole
                    // administrative area — this has to be somewhere a crew can
                    // actually be sent.
                    .queryParam("result_type", "street_address|route|premise|sublocality")
                    .build()
                    .toUriString();

            Map<String, Object> body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });

            if (body == null) {
                return Optional.empty();
            }

            String status = String.valueOf(body.get("status"));
            if ("ZERO_RESULTS".equals(status)) {
                return Optional.empty();
            }
            if (!"OK".equals(status)) {
                // OVER_QUERY_LIMIT, REQUEST_DENIED, INVALID_REQUEST. Logged
                // with the status so a misconfigured key is diagnosable, but
                // never surfaced to the caller.
                log.warn("Geocoding returned status {}", status);
                return Optional.empty();
            }

            Object resultsRaw = body.get("results");
            if (!(resultsRaw instanceof List<?> results) || results.isEmpty()) {
                return Optional.empty();
            }
            if (!(results.get(0) instanceof Map<?, ?> first)) {
                return Optional.empty();
            }

            Object formatted = first.get("formatted_address");
            return formatted == null ? Optional.empty() : Optional.of(String.valueOf(formatted));

        } catch (RuntimeException ex) {
            // Timeout, DNS, TLS, malformed body — all the same to the caller.
            log.warn("Geocoding call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Writes the result, including a negative one.
     *
     * A concurrent submission at the same spot can insert the same key first;
     * that is a duplicate lookup, not a fault, so the constraint violation is
     * swallowed rather than propagated into the caller's transaction. That is
     * also why this runs in REQUIRES_NEW — a failed cache write must not roll
     * back the complaint that triggered it.
     */
    private void store(GeocodeCache.Key key, Optional<String> address) {
        try {
            cacheRepository.save(GeocodeCache.builder()
                    .id(key)
                    .address(address.orElse(null))
                    .resolved(address.isPresent())
                    .build());
        } catch (RuntimeException ex) {
            log.debug("Geocode cache write skipped: {}", ex.getMessage());
        }
    }

    /**
     * Rounds to app.geocoding.cache-key-scale decimal places.
     *
     * The scale is the whole economics of this service: too fine and every
     * report is a fresh lookup, too coarse and two streets share an address.
     * See AppProperties.Geocoding for the measured basis of the default.
     */
    static GeocodeCache.Key keyFor(double latitude, double longitude, int scale) {
        return new GeocodeCache.Key(
                BigDecimal.valueOf(latitude).setScale(scale, RoundingMode.HALF_UP),
                BigDecimal.valueOf(longitude).setScale(scale, RoundingMode.HALF_UP));
    }
}
