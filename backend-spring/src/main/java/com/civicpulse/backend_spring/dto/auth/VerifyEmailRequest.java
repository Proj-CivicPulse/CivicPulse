package com.civicpulse.backend_spring.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The token arrives in a POST body rather than as a query parameter on a GET.
 *
 * The emailed link is a GET to the frontend, which then posts the token here.
 * That extra hop is deliberate: a token in a URL this service handled directly
 * would end up in access logs and in the Referer header of anything the landing
 * page loads. It also keeps the redemption non-idempotent-safe — mail scanners
 * and link previewers follow GETs, and would burn a single-use token before the
 * user ever clicked.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyEmailRequest {

    @NotBlank
    private String token;
}
