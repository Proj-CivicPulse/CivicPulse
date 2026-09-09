package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.config.JwtProperties;
import com.civicpulse.backend_spring.entity.RefreshToken;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.RevocationReason;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.repository.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
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
class RefreshTokenServiceTest {

    private static final String SESSION = "session-1";
    private static final String JTI = "jti-1";

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private SessionRevocationRegistry revocationRegistry;
    @Mock private JwtService jwtService;
    @Mock private Claims claims;

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-only-secret-value-not-used-anywhere-real-0123456789");
        jwtProperties.setAccessExpiration(900_000L);
        jwtProperties.setRefreshExpiration(604_800_000L);

        AppProperties appProperties = new AppProperties();
        appProperties.getAuth().setRefreshReuseGraceSeconds(30);

        service = new RefreshTokenService(
                refreshTokenRepository, revocationRegistry, jwtService, jwtProperties, appProperties);

        user = User.builder().id(42L).email("citizen@example.com").role(UserRole.CITIZEN).build();

        when(jwtService.extractTokenId(claims)).thenReturn(JTI);
        when(jwtService.extractSessionId(claims)).thenReturn(SESSION);
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(), any(), any(), any())).thenReturn("refresh-token");
    }

    private static RefreshToken live() {
        return RefreshToken.builder()
                .jti(JTI)
                .sessionId(SESSION)
                .userId(42L)
                .issuedAt(LocalDateTime.now().minusMinutes(5))
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    @Test
    @DisplayName("startSession issues a pair on a brand-new session id")
    void startSessionPersistsToken() {
        service.startSession(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());

        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().getJti()).isNotBlank();
        assertThat(saved.getValue().getSessionId()).isNotBlank();
        assertThat(saved.getValue().getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("rotate retires the presented token and issues a successor on the same session")
    void rotateRetiresPresentedToken() {
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(live()));

        RefreshTokenService.IssuedTokens tokens = service.rotate(user, claims);

        assertThat(tokens.accessToken()).isEqualTo("access-token");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(saved.capture());

        RefreshToken retired = saved.getAllValues().get(0);
        assertThat(retired.getRevokedReason()).isEqualTo(RevocationReason.ROTATED);
        assertThat(retired.getRevokedAt()).isNotNull();

        // The successor stays in the same session, so a later revoke still
        // catches the whole chain.
        assertThat(saved.getAllValues().get(1).getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getAllValues().get(1).getJti()).isNotEqualTo(JTI);
    }

    @Test
    @DisplayName("replaying a long-retired token revokes the whole session")
    void detectsReuse() {
        RefreshToken retired = live();
        retired.setRevokedAt(LocalDateTime.now().minusMinutes(10));
        retired.setRevokedReason(RevocationReason.ROTATED);
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.rotate(user, claims))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository)
                .revokeSession(eq(SESSION), any(), eq(RevocationReason.REUSE_DETECTED));
        verify(revocationRegistry).revoke(SESSION);
    }

    @Test
    @DisplayName("a concurrent two-tab refresh is not treated as reuse")
    void toleratesRotationRace() {
        RefreshToken justRotated = live();
        justRotated.setRevokedAt(LocalDateTime.now().minusSeconds(2));
        justRotated.setRevokedReason(RevocationReason.ROTATED);
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(justRotated));

        RefreshTokenService.IssuedTokens tokens = service.rotate(user, claims);

        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
        verify(refreshTokenRepository, never())
                .revokeSession(any(), any(), eq(RevocationReason.REUSE_DETECTED));
    }

    @Test
    @DisplayName("a token retired by sign-out is refused even within the grace window")
    void graceWindowDoesNotResurrectLogout() {
        RefreshToken loggedOut = live();
        loggedOut.setRevokedAt(LocalDateTime.now().minusSeconds(2));
        loggedOut.setRevokedReason(RevocationReason.LOGOUT);
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(loggedOut));

        assertThatThrownBy(() -> service.rotate(user, claims))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("an expired row is refused")
    void refusesExpiredToken() {
        RefreshToken expired = live();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(user, claims))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("an unknown token is refused rather than silently re-issued")
    void refusesUnknownToken() {
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(user, claims))
                .isInstanceOf(UnauthorizedException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("a pre-session token with no jti or sid is refused")
    void refusesLegacyToken() {
        when(jwtService.extractTokenId(claims)).thenReturn(null);
        when(jwtService.extractSessionId(claims)).thenReturn(null);

        assertThatThrownBy(() -> service.rotate(user, claims))
                .isInstanceOf(UnauthorizedException.class);
        verify(refreshTokenRepository, never()).findByJti(any());
    }

    @Test
    @DisplayName("signing out everywhere blocks each of the user's live sessions")
    void revokeAllForUserBlocksAccessTokensToo() {
        when(refreshTokenRepository.findActiveSessionIds(42L))
                .thenReturn(List.of("session-1", "session-2"));

        service.revokeAllForUser(42L, RevocationReason.LOGOUT_ALL);

        verify(refreshTokenRepository).revokeAllForUser(eq(42L), any(), eq(RevocationReason.LOGOUT_ALL));
        // Without this the access tokens already issued would stay usable for
        // the remainder of their lifetime.
        verify(revocationRegistry).revokeAll(List.of("session-1", "session-2"));
    }
}
