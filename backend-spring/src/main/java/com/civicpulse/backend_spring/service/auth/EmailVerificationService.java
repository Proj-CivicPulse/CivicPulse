package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import com.civicpulse.backend_spring.repository.UserRepository;
import com.civicpulse.backend_spring.service.email.AuthEmailComposer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Confirms that whoever registered an address can actually read mail sent to it.
 *
 * DELIBERATELY NOT A GATE ON SIGNING IN. An unverified account works exactly
 * like a verified one today; the flag is recorded and exposed on the user, and
 * that is all. Two reasons. Filing a complaint is already open and anonymous by
 * decision (docs/endpoints.md), so making an account harder to use than no
 * account at all would push people to the anonymous path and lose the reporter
 * contact the flag exists to establish. And a hard gate turns any mail failure —
 * a wrong address, a provider outage, an over-eager spam filter — into a
 * permanently locked account with no self-service way out.
 *
 * What it is for: knowing which contact addresses are real before anything is
 * ever sent to them, and giving the UI something to nudge on. Tightening it into
 * a gate later is a policy change here, not a redesign.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final AuthEmailComposer emailComposer;
    private final AppProperties appProperties;

    /** Issues a fresh link and mails it. Silently does nothing if already verified. */
    @Transactional
    public void sendVerificationEmail(User user) {
        if (user.getEmailVerifiedAt() != null) {
            return;
        }
        Duration ttl = Duration.ofHours(appProperties.getEmail().getVerificationTtlHours());
        String token = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION, ttl);
        emailComposer.sendVerification(user.getEmail(), user.getName(), token);
    }

    /**
     * Redeems a verification link.
     *
     * @throws com.civicpulse.backend_spring.exception.ValidationException
     *         if the token is unknown, expired, used, or of the wrong purpose
     */
    @Transactional
    public void verify(String token) {
        Long userId = authTokenService.consume(token, AuthTokenPurpose.EMAIL_VERIFICATION);

        userRepository.findById(userId).ifPresent(user -> {
            // Re-verifying is a no-op rather than an error: a double-clicked
            // link, or one opened twice by a mail scanner, is not a failure
            // worth showing the user.
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(LocalDateTime.now());
                userRepository.save(user);
                log.info("Email verified for user {}", userId);
            }
        });
    }
}
