package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import com.civicpulse.backend_spring.enums.RevocationReason;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.UserRepository;
import com.civicpulse.backend_spring.service.email.AuthEmailComposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthTokenService authTokenService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuthEmailComposer emailComposer;

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, passwordEncoder, authTokenService,
                refreshTokenService, emailComposer, new AppProperties());

        user = User.builder()
                .id(7L).name("Asha").email("citizen@example.com")
                .passwordHash("$2a$10$old").role(UserRole.CITIZEN)
                .emailVerifiedAt(LocalDateTime.now().minusDays(3))
                .build();

        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$new");
        when(authTokenService.issue(any(), any(), any())).thenReturn("raw-token");
    }

    @Test
    @DisplayName("a known address is mailed a reset link")
    void mailsKnownAddress() {
        when(userRepository.findByEmail("citizen@example.com")).thenReturn(Optional.of(user));

        service.requestReset("citizen@example.com");

        verify(emailComposer).sendPasswordReset("citizen@example.com", "Asha", "raw-token");
    }

    @Test
    @DisplayName("an unknown address is a silent no-op, not an error")
    void unknownAddressLooksIdentical() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        // Throwing, or reporting "no such account", would let anyone test
        // addresses in bulk — the same leak the login path avoids.
        assertThatCode(() -> service.requestReset("nobody@example.com")).doesNotThrowAnyException();
        verify(emailComposer, never()).sendPasswordReset(any(), any(), any());
    }

    @Test
    @DisplayName("the address is normalised before lookup")
    void normalisesAddress() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        service.requestReset("  Citizen@Example.COM ");

        verify(userRepository).findByEmail("citizen@example.com");
    }

    @Test
    @DisplayName("a successful reset stores the new hash and revokes every session")
    void resetRevokesAllSessions() {
        when(authTokenService.consume("raw-token", AuthTokenPurpose.PASSWORD_RESET)).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.reset("raw-token", "N3wPassw0rd!");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$new");

        // The reason someone resets is usually that another person may be
        // signed in right now; leaving that session alive defeats the exercise.
        verify(refreshTokenService).revokeAllForUser(7L, RevocationReason.PASSWORD_RESET);
    }

    @Test
    @DisplayName("completing a reset also verifies the address")
    void resetVerifiesUnverifiedAddress() {
        user.setEmailVerifiedAt(null);
        when(authTokenService.consume(any(), any())).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.reset("raw-token", "N3wPassw0rd!");

        // Holding the link already proves control of the mailbox, which is all
        // verification asks for — no reason to make them do it twice.
        assertThat(user.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("an invalid token changes nothing")
    void invalidTokenChangesNothing() {
        when(authTokenService.consume(any(), any()))
                .thenThrow(new ValidationException("That link is invalid or has expired. Request a new one."));

        assertThatThrownBy(() -> service.reset("bad", "N3wPassw0rd!"))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any(), any());
    }

    @Test
    @DisplayName("a token for an account deleted since is refused")
    void missingUserIsRefused() {
        when(authTokenService.consume(any(), any())).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset("raw-token", "N3wPassw0rd!"))
                .isInstanceOf(ValidationException.class);
        verify(refreshTokenService, never()).revokeAllForUser(eq(7L), any());
    }
}
