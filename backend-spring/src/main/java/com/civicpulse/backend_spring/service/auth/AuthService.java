package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.dto.auth.RegisterRequest;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.EmailAlreadyExistsException;
import com.civicpulse.backend_spring.exception.InvalidCredentialsException;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * A real BCrypt hash of a value nobody knows, used to spend the same time
     * on an unknown email as on a known one (see {@link #authenticate}).
     *
     * Built by the configured encoder at startup rather than hardcoded, so it
     * always carries the same cost factor as the hashes it stands in for — a
     * stale constant at a lower cost would reintroduce the very timing gap it
     * exists to close. The input is random per boot: this hash must never
     * match a password anyone could actually type.
     */
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyPasswordHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Self-service registration always creates a CITIZEN. Officer accounts
     * are provisioned deliberately, never by public signup — see
     * "Creating an officer" in the service README.
     */
    @Transactional
    public User register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        User user = User.builder()
                .name(request.getName())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.CITIZEN)
                .build();

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent registrations for the same address both pass
            // the existsByEmail check; the unique constraint is what
            // actually decides. Report it as the same 409, not a 500.
            throw new EmailAlreadyExistsException("Email already registered");
        }
    }

    public User authenticate(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.getEmail()))
                .orElse(null);

        // ALWAYS verify a hash, even when there is no such user.
        //
        // The identical error message is only half of enumeration resistance.
        // Short-circuiting on `user == null` would skip BCrypt entirely and
        // answer in a millisecond, while a real address costs the full ~100 ms
        // of hashing — a gap so wide it is measurable over the internet, and it
        // reveals exactly what the shared message is meant to hide. Verifying
        // against a throwaway hash makes both paths do the same work.
        String hashToVerify = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToVerify);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return user;
    }

    /**
     * Loads the user a token refers to. The token may still be
     * cryptographically valid after the account is deleted, so this is a
     * real lookup, not a claims read.
     */
    public User requireById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));
    }

    /** Emails are case-insensitive identifiers; store and compare them one way. */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
