package com.civicpulse.backend_spring.service.photo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The bucket, as the photo flow needs it (docs/photo-upload-contract.md).
 * Provider-neutral: {@link S3PhotoStorage} speaks the S3 API, which Railway
 * Buckets and MinIO both serve; tests use an in-memory fake.
 */
public interface PhotoStorage {

    /** What storage says it holds — never what the client claimed. */
    record ObjectInfo(long sizeBytes, String contentType, Instant lastModified) {
    }

    /** False when no bucket is configured; every other method then refuses. */
    boolean isEnabled();

    /** A URL the browser can PUT exactly this type and length to, until it expires. */
    String presignPut(String key, String contentType, long sizeBytes, Duration ttl);

    /** A URL the browser can GET the object from, until it expires. */
    String presignGet(String key, Duration ttl);

    Optional<ObjectInfo> head(String key);

    /** The object's bytes, reading at most {@code maxBytes}. */
    byte[] get(String key, long maxBytes);

    void put(String key, byte[] bytes, String contentType);

    void delete(String key);

    /** Keys under {@code prefix} last modified before {@code cutoff}. */
    List<String> listOlderThan(String prefix, Instant cutoff);
}
