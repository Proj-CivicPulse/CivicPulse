package com.civicpulse.backend_spring.dto.complaint;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of POST /complaints/{id}/photo-intent ({@code contentType},
 * {@code sizeBytes}) and POST /complaints/{id}/photo ({@code objectKey}).
 * {@code uploadToken} is the create response's token, for a submitter
 * without an account; a signed-in owner may leave it out.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhotoRequest {

    private String contentType;
    private Long sizeBytes;
    private String objectKey;
    private String uploadToken;
}
