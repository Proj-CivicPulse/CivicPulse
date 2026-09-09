package com.civicpulse.backend_spring.dto.auth;

import com.civicpulse.backend_spring.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The public shape of a user. Mirrors {@code AuthUser} in
 * frontend/src/stores/auth.store.ts exactly:
 * {@code { id: string; name: string; role: 'citizen' | 'officer' }}.
 *
 * Two deliberate conversions:
 * <ul>
 *   <li><b>id as String</b> — the frontend types every id as a string, and
 *       serialising a Java {@code Long} as a JSON number would also risk
 *       precision loss in JS beyond 2^53. Stringifying keeps one convention
 *       and survives a later switch to UUIDs.</li>
 *   <li><b>role lowercased</b> — Java enums are {@code CITIZEN}/{@code OFFICER};
 *       the frontend union is {@code 'citizen' | 'officer'}.</li>
 * </ul>
 *
 * Controllers return DTOs, never entities: entities carry the password hash
 * and lazy associations that would leak or blow up during serialisation.
 */
@Getter
@AllArgsConstructor
public class UserDto {

    private final String id;
    private final String name;
    private final String role;

    /**
     * Whether the address has been confirmed. Exposed so the UI can prompt;
     * it does not gate anything server-side (see EmailVerificationService).
     */
    private final boolean emailVerified;

    public static UserDto from(User user) {
        return new UserDto(
                String.valueOf(user.getId()),
                user.getName(),
                user.getRole().name().toLowerCase(),
                user.getEmailVerifiedAt() != null
        );
    }
}
