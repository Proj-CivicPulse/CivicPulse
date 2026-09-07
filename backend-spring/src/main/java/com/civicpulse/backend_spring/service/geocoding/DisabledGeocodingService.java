package com.civicpulse.backend_spring.service.geocoding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The default. Addresses stay null and everything else works normally.
 *
 * Active whenever app.geocoding.provider is anything other than "google",
 * which includes a fresh clone with no API key configured. Geocoding is
 * optional by design — a contributor should be able to run this project
 * without a billing account.
 */
@Service
@ConditionalOnProperty(name = "app.geocoding.provider", havingValue = "none", matchIfMissing = true)
public class DisabledGeocodingService implements GeocodingService {

    @Override
    public Optional<String> reverseGeocode(double latitude, double longitude) {
        return Optional.empty();
    }
}
