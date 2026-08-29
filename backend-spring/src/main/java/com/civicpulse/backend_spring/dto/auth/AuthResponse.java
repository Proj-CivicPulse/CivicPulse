package com.civicpulse.backend_spring.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Login/refresh response body: {@code { "user": { id, name, role } }}.
 *
 * No token here on purpose — the access and refresh tokens travel as
 * httpOnly cookies (see AuthCookieFactory), which JavaScript cannot read.
 * frontend/src/services/auth.service.ts destructures exactly this shape.
 */
@Getter
@AllArgsConstructor
public class AuthResponse {

    private final UserDto user;
}
