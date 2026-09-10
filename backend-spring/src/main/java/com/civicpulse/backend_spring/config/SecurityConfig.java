package com.civicpulse.backend_spring.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorHandlers securityErrorHandlers;
    private final AppProperties appProperties;
    private final InternalTokenAuthorizationManager internalTokenAuthorizationManager;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Allowed origins come from configuration (FRONTEND_ORIGIN, comma-separated)
     * — never a blanket wildcard. {@code allowCredentials} is required for the
     * httpOnly auth cookie, and the CORS spec forbids pairing it with "*".
     *
     * Uses {@code setAllowedOriginPatterns} rather than {@code setAllowedOrigins}
     * so an entry may contain a "*" segment, e.g. https://myapp-*.vercel.app.
     * Vercel mints a new preview URL per commit, so an exact-match list cannot
     * cover them. A pattern is still an allowlist — it matches a specific host
     * shape — which is categorically different from the bare "*" rejected below.
     *
     * In local dev the frontend actually reaches this service through the
     * Vite proxy (same-origin), so CORS is not exercised; this exists for
     * any deployment where the browser talks to the API directly.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = appProperties.getFrontendOrigin();

        // Fail fast and loudly. "*" here would be silently downgraded by the
        // browser once credentials are involved, producing a CORS failure that
        // looks like a server bug rather than a configuration mistake.
        if (origins.stream().anyMatch(origin -> "*".equals(origin.trim()))) {
            throw new IllegalStateException(
                    "FRONTEND_ORIGIN must not contain a bare \"*\": credentials are enabled, "
                            + "and the CORS spec forbids that combination. List the origins "
                            + "explicitly, or use a host pattern such as https://myapp-*.vercel.app.");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Without this the browser hides Retry-After from JS, so a rate-limited
        // client could not tell the user when to try again from the header.
        configuration.setExposedHeaders(List.of("Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Safe to disable ONLY because the auth cookies are
                // SameSite=Lax, so a cross-site request never carries them.
                // Re-enable CSRF tokens if any cookie ever becomes SameSite=None.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(securityErrorHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(securityErrorHandlers.accessDeniedHandler())
                )
                .authorizeHttpRequests(auth -> auth
                        // Liveness/readiness probe — must stay reachable
                        // even when everything else is locked down.
                        .requestMatchers("/health").permitAll()

                        // Login, register, logout, and refresh are how a
                        // caller *gets* credentials, so they cannot require them.
                        //
                        // NOT /auth/logout-all: revoking every session for a
                        // user must be something only that user can ask for, so
                        // it falls through to anyRequest().authenticated().
                        // These are exact-path matchers, so it is not swept in
                        // by the "/auth/logout" entry.
                        //
                        // The three recovery routes are public for the same
                        // reason: whoever is verifying an address or resetting
                        // a forgotten password is by definition not signed in,
                        // and the emailed single-use token IS the credential.
                        // NOT /auth/resend-verification — that one takes the
                        // address from the session precisely so it cannot be
                        // aimed at somebody else's inbox.
                        .requestMatchers("/auth/login", "/auth/register",
                                "/auth/logout", "/auth/refresh",
                                "/auth/verify-email", "/auth/forgot-password",
                                "/auth/reset-password").permitAll()

                        // Public municipal reference data: ward names, zones,
                        // centroids, and the landing page open-incident counts.
                        // GET only, so any future write endpoint under /wards
                        // stays default-deny.
                        .requestMatchers(HttpMethod.GET, "/wards", "/wards/**").permitAll()

                        // Service-to-service. Shared-secret header, and it
                        // fails closed when the secret is unset — Node holds no
                        // user session, so this cannot be role-gated.
                        .requestMatchers("/internal/**")
                                .access(internalTokenAuthorizationManager)

                        // Submitting a complaint is open/anonymous by
                        // decision (docs/endpoints.md); tracking your own
                        // complaints below is not.
                        .requestMatchers(HttpMethod.POST, "/complaints").permitAll()

                        .requestMatchers("/complaints/mine").hasRole("CITIZEN")

                        // Officer-only writes and reads over complaint data.
                        // Without the PATCH rule this path fell through to
                        // anyRequest().authenticated(), which would let ANY
                        // signed-in citizen change the status of ANYONE else's
                        // complaint. ComplaintController repeats it as
                        // @PreAuthorize; both are deliberate.
                        .requestMatchers(HttpMethod.PATCH, "/complaints/*").hasRole("OFFICER")
                        .requestMatchers(HttpMethod.GET, "/complaints").hasRole("OFFICER")

                        .requestMatchers("/incidents/**", "/dashboard/**", "/analytics/**")
                                .hasRole("OFFICER")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }
}
