package com.civicpulse.backend_spring.exception;

/** No photo storage is configured on this server: 503 PHOTO_UPLOAD_UNAVAILABLE. */
public class PhotoUnavailableException extends RuntimeException {

    public PhotoUnavailableException() {
        super("Photo upload is not available on this server");
    }
}
