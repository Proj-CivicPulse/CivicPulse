package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.entity.AuthToken;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.AuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthTokenServiceTest {

    @Mock private AuthTokenRepository authTokenRepository;

    private AuthTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(authTokenRepository);
        user = User.builder().id(7L).email("citizen@example.com").role(UserRole.CITIZEN).build();
    }

    private static AuthToken stored(String rawToken, AuthTokenPurpose purpose) {
        return AuthToken.builder()
                .tokenHash(AuthTokenService.hash(rawToken))
                .purpose(purpose)
                .userId(7L)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    @DisplayName("the raw token is returned but only its hash is stored")
    void storesOnlyTheHash() {
        String token = service.issue(user, AuthTokenPurpose.PASSWORD_RESET, Duration.ofHours(1));

        ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
        verify(authTokenRepository).save(saved.capture());

        // The single most important property of this table: a leaked copy of it
        // must not contain anything that can be presented back to the API.
        assertThat(saved.getValue().getTokenHash())
                .isNotEqualTo(token)
                .hasSize(64)
                .isEqualTo(AuthTokenService.hash(token));
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("issuing retires the user's outstanding tokens of that purpose")
    void issuingInvalidatesPrevious() {
        service.issue(user, AuthTokenPurpose.PASSWORD_RESET, Duration.ofHours(1));

        // Otherwise every link ever mailed stays live until expiry, and an old
        // message sitting in an inbox is still a way in.
        verify(authTokenRepository)
                .consumeOutstanding(eq(7L), eq(AuthTokenPurpose.PASSWORD_RESET), any());
    }

    @Test
    @DisplayName("two issues never produce the same token")
    void tokensAreUnique() {
        String first = service.issue(user, AuthTokenPurpose.PASSWORD_RESET, Duration.ofHours(1));
        String second = service.issue(user, AuthTokenPurpose.PASSWORD_RESET, Duration.ofHours(1));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a valid token redeems and is marked used")
    void consumesValidToken() {
        String token = "raw-token-value";
        AuthToken row = stored(token, AuthTokenPurpose.PASSWORD_RESET);
        when(authTokenRepository.findByTokenHash(AuthTokenService.hash(token)))
                .thenReturn(Optional.of(row));

        assertThat(service.consume(token, AuthTokenPurpose.PASSWORD_RESET)).isEqualTo(7L);

        // Single use is enforced on this column.
        assertThat(row.getConsumedAt()).isNotNull();
        verify(authTokenRepository).save(row);
    }

    @Test
    @DisplayName("a token issued for verification cannot be spent on a password reset")
    void enforcesPurpose() {
        String token = "raw-token-value";
        when(authTokenRepository.findByTokenHash(AuthTokenService.hash(token)))
                .thenReturn(Optional.of(stored(token, AuthTokenPurpose.EMAIL_VERIFICATION)));

        // A verification link is far easier to obtain than a reset link;
        // without this check it would be an account takeover.
        assertThatThrownBy(() -> service.consume(token, AuthTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(ValidationException.class);
        verify(authTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("an already-used token is refused")
    void refusesReuse() {
        String token = "raw-token-value";
        AuthToken row = stored(token, AuthTokenPurpose.PASSWORD_RESET);
        row.setConsumedAt(LocalDateTime.now().minusMinutes(1));
        when(authTokenRepository.findByTokenHash(AuthTokenService.hash(token)))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.consume(token, AuthTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an expired token is refused")
    void refusesExpired() {
        String token = "raw-token-value";
        AuthToken row = stored(token, AuthTokenPurpose.PASSWORD_RESET);
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(authTokenRepository.findByTokenHash(AuthTokenService.hash(token)))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.consume(token, AuthTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("every failure mode gives the same message")
    void doesNotDistinguishFailureModes() {
        when(authTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        // Saying "expired" rather than "never existed" would confirm to a
        // stranger that a token they hold was genuine.
        assertThatThrownBy(() -> service.consume("unknown", AuthTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(ValidationException.class)
                .hasMessage("That link is invalid or has expired. Request a new one.");

        assertThatThrownBy(() -> service.consume(null, AuthTokenPurpose.PASSWORD_RESET))
                .isInstanceOf(ValidationException.class)
                .hasMessage("That link is invalid or has expired. Request a new one.");
    }
}
