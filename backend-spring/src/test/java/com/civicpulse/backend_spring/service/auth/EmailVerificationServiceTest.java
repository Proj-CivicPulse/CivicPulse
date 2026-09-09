package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private AuthEmailComposer emailComposer;

    private EmailVerificationService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                userRepository, authTokenService, emailComposer, new AppProperties());

        user = User.builder()
                .id(7L).name("Asha").email("citizen@example.com").role(UserRole.CITIZEN)
                .build();

        when(authTokenService.issue(any(), any(), any())).thenReturn("raw-token");
    }

    @Test
    @DisplayName("an unverified user is mailed a link")
    void mailsUnverifiedUser() {
        service.sendVerificationEmail(user);

        verify(emailComposer).sendVerification("citizen@example.com", "Asha", "raw-token");
    }

    @Test
    @DisplayName("an already-verified user is not mailed again")
    void skipsVerifiedUser() {
        user.setEmailVerifiedAt(LocalDateTime.now().minusDays(1));

        service.sendVerificationEmail(user);

        verify(emailComposer, never()).sendVerification(any(), any(), any());
        verify(authTokenService, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("a valid token marks the address verified")
    void verifiesUser() {
        when(authTokenService.consume("raw-token", AuthTokenPurpose.EMAIL_VERIFICATION)).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.verify("raw-token");

        assertThat(user.getEmailVerifiedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("verifying twice is a no-op, not a failure")
    void reverificationIsHarmless() {
        LocalDateTime original = LocalDateTime.now().minusDays(2);
        user.setEmailVerifiedAt(original);
        when(authTokenService.consume(any(), any())).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.verify("raw-token");

        // A double-clicked link, or one opened by a mail scanner, should not
        // look like an error — and must not move the original timestamp.
        assertThat(user.getEmailVerifiedAt()).isEqualTo(original);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an invalid token verifies nothing")
    void invalidTokenVerifiesNothing() {
        when(authTokenService.consume(any(), any()))
                .thenThrow(new ValidationException("That link is invalid or has expired. Request a new one."));

        assertThatThrownBy(() -> service.verify("bad")).isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
    }
}
