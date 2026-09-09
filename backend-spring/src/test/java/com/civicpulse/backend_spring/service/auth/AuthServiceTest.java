package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.InvalidCredentialsException;
import com.civicpulse.backend_spring.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String DUMMY_HASH = "$2a$10$dummyhashusedwhentheaccountdoesnotexist000000000000";
    private static final String REAL_HASH = "$2a$10$realhashfortheseededcitizenaccount0000000000000000000";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService = new AuthService(userRepository, passwordEncoder);
        authService.initDummyPasswordHash();
    }

    private static LoginRequest login(String email, String password) {
        return new LoginRequest(email, password);
    }

    @Test
    @DisplayName("an unknown email still costs a full password verification")
    void unknownEmailStillHashes() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(login("ghost@example.com", "hunter2")))
                .isInstanceOf(InvalidCredentialsException.class);

        // The whole point: without this call the response returns in about a
        // millisecond while a real account costs ~100ms of BCrypt, and that
        // gap tells an attacker which addresses are registered — exactly what
        // the shared error message is there to conceal.
        verify(passwordEncoder).matches("hunter2", DUMMY_HASH);
    }

    @Test
    @DisplayName("a wrong password for a real account fails the same way")
    void wrongPasswordIsRejected() {
        User user = User.builder()
                .id(1L).email("citizen@example.com").passwordHash(REAL_HASH).role(UserRole.CITIZEN)
                .build();
        when(userRepository.findByEmail("citizen@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(login("citizen@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                // Identical message to the unknown-email case.
                .hasMessage("Invalid email or password");

        verify(passwordEncoder).matches("wrong", REAL_HASH);
    }

    @Test
    @DisplayName("correct credentials return the user")
    void acceptsCorrectCredentials() {
        User user = User.builder()
                .id(1L).email("citizen@example.com").passwordHash(REAL_HASH).role(UserRole.CITIZEN)
                .build();
        when(userRepository.findByEmail("citizen@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", REAL_HASH)).thenReturn(true);

        assertThat(authService.authenticate(login("citizen@example.com", "correct"))).isSameAs(user);
    }

    @Test
    @DisplayName("the email is normalised before lookup, so casing cannot bypass the account")
    void normalisesEmailOnLogin() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(login("  Citizen@Example.COM ", "x")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository).findByEmail(eq("citizen@example.com"));
    }

    @Test
    @DisplayName("the stand-in hash is produced by the real encoder, so it carries the same cost")
    void dummyHashComesFromTheConfiguredEncoder() {
        // A hardcoded constant would drift from the encoder's strength and
        // quietly reopen the timing gap; it must be encoded at startup.
        verify(passwordEncoder).encode(anyString());
    }
}
