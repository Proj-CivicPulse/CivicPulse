package com.civicpulse.backend_spring.service.photo;

import com.civicpulse.backend_spring.config.AppProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link PhotoStorage} over the S3 API. Built only when a bucket is configured
 * (see {@link PhotoStorageConfig}).
 */
public class S3PhotoStorage implements PhotoStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3PhotoStorage(AppProperties.Photos config) {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey()));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(config.isPathStyle()).build();
        Region region = Region.of(config.getRegion() == null || config.getRegion().isBlank()
                ? "auto" : config.getRegion());

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config);
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config);
        if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            URI endpoint = URI.create(config.getEndpoint());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        this.bucket = config.getBucket();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Lets the frontend's origins PUT to and GET from the bucket — the browser
     * talks to storage directly, so without this rule every upload fails its
     * CORS preflight. Applied by the app itself on startup, from the same
     * FRONTEND_ORIGIN list the API's own CORS uses, so the two cannot drift
     * and there is no manual bucket step to forget. Replaces any existing
     * rule on the bucket.
     */
    public void applyCors(List<String> origins) {
        CORSRule rule = CORSRule.builder()
                .allowedOrigins(origins)
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("content-type")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
        client.putBucketCors(r -> r.bucket(bucket).corsConfiguration(c -> c.corsRules(rule)));
    }

    @Override
    public String presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
        // Type and length are part of the signature, so storage refuses a PUT
        // that differs from what was permitted.
        return presigner.presignPutObject(r -> r.signatureDuration(ttl)
                        .putObjectRequest(p -> p.bucket(bucket).key(key)
                                .contentType(contentType).contentLength(sizeBytes)))
                .url().toString();
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(r -> r.signatureDuration(ttl)
                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url().toString();
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse h = client.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(h.contentLength(), h.contentType(), h.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public byte[] get(String key, long maxBytes) {
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(r -> r.bucket(bucket).key(key))) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new IllegalStateException("object " + key + " is larger than " + maxBytes + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        client.putObject(r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public List<String> listOlderThan(String prefix, Instant cutoff) {
        return client.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents().stream()
                .filter(o -> o.lastModified() != null && o.lastModified().isBefore(cutoff))
                .map(S3Object::key)
                .toList();
    }
}
