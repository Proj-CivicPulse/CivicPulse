package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.dto.auth.RegisterRequest;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.EmailAlreadyExistsException;
import com.civicpulse.backend_spring.exception.InvalidCredentialsException;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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

        // Same message and timing-insensitive shape for both failure modes:
        // distinguishing "unknown email" from "wrong password" lets an
        // attacker enumerate which addresses are registered.
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
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
