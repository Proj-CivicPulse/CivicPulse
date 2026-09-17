package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.repository.WardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The default resolver since V13.
 *
 * <p>The containment logic itself lives in PostGIS and is verified against the
 * real imported boundaries by {@code scripts/verify-ward-import.mjs} — all 243
 * representative points resolving to exactly one ward, and four out-of-city
 * points resolving to none. That is where the geometry is proven; there is no
 * value in re-implementing ST_Contains here to check it agrees with itself.
 *
 * <p>What THIS test pins down is the contract around the query, which is where
 * a regression would be silent: latitude and longitude must not be transposed,
 * and a point outside every ward must come back empty rather than falling
 * through to a nearest-ward guess.
 */
@ExtendWith(MockitoExtension.class)
class PostGisWardResolverTest {

    @Mock
    private WardRepository wardRepository;

    @InjectMocks
    private PostGisWardResolver resolver;

    @Test
    @DisplayName("returns the containing ward")
    void returnsContainingWard() {
        Ward ward = Ward.builder().id(42L).code("17").name("Jayanagar").build();
        when(wardRepository.findContaining(anyDouble(), anyDouble())).thenReturn(Optional.of(ward));

        assertThat(resolver.resolve(12.9250, 77.5938)).contains(ward);
    }

    @Test
    @DisplayName("passes latitude and longitude in that order, never transposed")
    void doesNotTransposeCoordinates() {
        // ST_MakePoint takes X then Y — longitude then LATITUDE — the reverse of
        // how every caller in this codebase says it. Swapping them yields a
        // point in the Indian Ocean and a silent 404 that looks exactly like
        // "you are outside the city", so it is worth a test of its own.
        when(wardRepository.findContaining(anyDouble(), anyDouble())).thenReturn(Optional.empty());

        resolver.resolve(12.9716, 77.5946);

        ArgumentCaptor<Double> lat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> lng = ArgumentCaptor.forClass(Double.class);
        verify(wardRepository).findContaining(lat.capture(), lng.capture());

        assertThat(lat.getValue()).isEqualTo(12.9716);
        assertThat(lng.getValue()).isEqualTo(77.5946);
    }

    @Test
    @DisplayName("a point outside every boundary resolves to nothing — no nearest-ward fallback")
    void noFallbackWhenOutsideEveryWard() {
        when(wardRepository.findContaining(anyDouble(), anyDouble())).thenReturn(Optional.empty());

        // Mysuru, 150 km away. Empty is the correct ANSWER, not a failure: the
        // caller renders it as a 404 and the submit form falls back to the ward
        // picker. Quietly substituting the least-distant ward here would
        // reintroduce exactly the invisible mis-filing that point-in-polygon
        // was imported to remove.
        assertThat(resolver.resolve(12.2958, 76.6394)).isEmpty();

        // And it must not go looking for a consolation prize.
        verify(wardRepository).findContaining(12.2958, 76.6394);
        verifyNoMoreInteractions(wardRepository);
    }
}
