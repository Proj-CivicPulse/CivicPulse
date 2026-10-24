package com.civicpulse.backend_spring.service.photo;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Photo storage is optional: with no bucket configured the app runs exactly as
 * before, and the photo endpoints answer 503 PHOTO_UPLOAD_UNAVAILABLE.
 */
@Configuration
@Slf4j
public class PhotoStorageConfig {

    @Bean
    PhotoStorage photoStorage(AppProperties properties) {
        AppProperties.Photos config = properties.getPhotos();
        if (!config.isEnabled()) {
            log.info("Photo storage: off (PHOTO_STORAGE_BUCKET unset)");
            return DISABLED;
        }
        log.info("Photo storage: bucket {} at {}", config.getBucket(),
                config.getEndpoint() == null || config.getEndpoint().isBlank() ? "AWS default" : config.getEndpoint());
        S3PhotoStorage storage = new S3PhotoStorage(config);
        List<String> origins = properties.getFrontendOrigin() == null ? List.of() : properties.getFrontendOrigin();
        try {
            storage.applyCors(origins);
            log.info("Photo storage: CORS allows PUT/GET from {}", origins);
        } catch (RuntimeException e) {
            // Not fatal: the API still works, and a bucket whose CORS is set
            // by hand still takes uploads. Loud, because otherwise every
            // browser upload fails its preflight with nothing in this log.
            log.warn("Photo storage: could not set the bucket's CORS rule ({}). Browser uploads will fail "
                    + "unless the bucket allows PUT from {}", e.getMessage(), origins);
        }
        return storage;
    }

    /** Refuses everything; callers check {@link PhotoStorage#isEnabled()} first. */
    static final PhotoStorage DISABLED = new PhotoStorage() {
        @Override public boolean isEnabled() { return false; }
        @Override public String presignPut(String key, String contentType, long sizeBytes, Duration ttl) { throw off(); }
        @Override public String presignGet(String key, Duration ttl) { throw off(); }
        @Override public Optional<ObjectInfo> head(String key) { throw off(); }
        @Override public byte[] get(String key, long maxBytes) { throw off(); }
        @Override public void put(String key, byte[] bytes, String contentType) { throw off(); }
        @Override public void delete(String key) { throw off(); }
        @Override public List<String> listOlderThan(String prefix, Instant cutoff) { return List.of(); }

        private IllegalStateException off() {
            return new IllegalStateException("photo storage is not configured");
        }
    };
}
