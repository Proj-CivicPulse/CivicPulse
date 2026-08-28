package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.dto.auth.AuthResponse;
import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.dto.auth.RegisterRequest;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.UserRole;
import com.civicpulse.backend_spring.exception.EmailAlreadyExistsException;
import com.civicpulse.backend_spring.exception.InvalidCredentialsException;
import com.civicpulse.backend_spring.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public void register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.CITIZEN)
                .build();

        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(
                user.getId(),
                user.getRole().name()
        );

        return new AuthResponse(token);
    }
}