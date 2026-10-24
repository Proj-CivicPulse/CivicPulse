package com.civicpulse.backend_spring.service.photo;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.exception.PhotoUnavailableException;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The photo flow against an in-memory bucket: who may upload, what the
 * server accepts, and what it actually stores.
 */
class ComplaintPhotoServiceTest {

    private static final long ID = 41L;
    private static final long OWNER = 7L;

    /** A bucket in a map; "uploads" by the browser are simulated with put(). */
    static class FakeStorage implements PhotoStorage {
        final Map<String, byte[]> objects = new HashMap<>();
        final Map<String, String> types = new HashMap<>();
        final Map<String, Instant> modified = new HashMap<>();

        @Override public boolean isEnabled() { return true; }
        @Override public String presignPut(String key, String type, long size, Duration ttl) {
            return "https://bucket.test/" + key + "?put&type=" + type + "&len=" + size;
        }
        @Override public String presignGet(String key, Duration ttl) { return "https://bucket.test/" + key + "?get"; }
        @Override public Optional<ObjectInfo> head(String key) {
            return Optional.ofNullable(objects.get(key))
                    .map(b -> new ObjectInfo(b.length, types.get(key), modified.get(key)));
        }
        @Override public byte[] get(String key, long max) { return objects.get(key); }
        @Override public void put(String key, byte[] bytes, String type) {
            objects.put(key, bytes);
            types.put(key, type);
            modified.put(key, Instant.now());
        }
        @Override public void delete(String key) { objects.remove(key); }
        @Override public List<String> listOlderThan(String prefix, Instant cutoff) {
            return objects.keySet().stream().filter(k -> k.startsWith(prefix))
                    .filter(k -> modified.get(k).isBefore(cutoff)).toList();
        }
    }

    private FakeStorage storage;
    private PhotoUploadTokens tokens;
    private ComplaintPhotoService service;
    private Complaint complaint;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        tokens = new PhotoUploadTokens("a-test-secret-that-is-long-enough-123456", 7200, Clock.systemUTC());
        complaint = Complaint.builder().id(ID).referenceNo("CP-TEST-1").description("Open drain")
                .user(User.builder().id(OWNER).build()).build();
        ComplaintRepository repo = mock(ComplaintRepository.class);
        when(repo.findById(ID)).thenReturn(Optional.of(complaint));
        when(repo.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ComplaintPhotoService(repo, storage, tokens, new AppProperties());
    }

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new Color(30, 120, 90, 200));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Intent, then the browser's PUT, as the anonymous submitter. */
    private String upload(byte[] bytes) {
        String token = tokens.issue(ID);
        var intent = service.intent(ID, "image/png", (long) bytes.length, token, null);
        storage.put(intent.objectKey(), bytes, "image/png");
        return intent.objectKey();
    }

    @Test
    @DisplayName("the submitter's token, or the owner's session, may upload; anyone else gets a 404")
    void whoMayUpload() {
        String token = tokens.issue(ID);
        assertThat(service.intent(ID, "image/jpeg", 1000L, token, null).objectKey())
                .startsWith("uploads/41/");
        assertThat(service.intent(ID, "image/jpeg", 1000L, null, OWNER).uploadUrl()).contains("type=image/jpeg");

        assertThatThrownBy(() -> service.intent(ID, "image/jpeg", 1000L, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.intent(ID, "image/jpeg", 1000L, tokens.issue(42L), null))
                .isInstanceOf(ResourceNotFoundException.class);              // another complaint's token
        assertThatThrownBy(() -> service.intent(ID, "image/jpeg", 1000L, token + "x", 99L))
                .isInstanceOf(ResourceNotFoundException.class);              // tampered, not the owner
    }

    @Test
    @DisplayName("tokens expire")
    void tokensExpire() {
        PhotoUploadTokens old = new PhotoUploadTokens("a-test-secret-that-is-long-enough-123456", 60,
                Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
        assertThat(tokens.verify(old.issue(ID), ID)).isFalse();
    }

    @Test
    @DisplayName("only JPEG, PNG and WebP, and at most 8 MB, are permitted")
    void intentLimits() {
        String token = tokens.issue(ID);
        assertThatThrownBy(() -> service.intent(ID, "image/svg+xml", 1000L, token, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.intent(ID, "image/heic", 1000L, token, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.intent(ID, "image/png", 8L * 1024 * 1024 + 1, token, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("confirm re-encodes to a metadata-free JPEG plus thumbnail, and deletes the raw upload")
    void confirmStoresNormalisedPhoto() throws IOException {
        String key = upload(png(3000, 1500));

        var dto = service.confirm(ID, key, tokens.issue(ID), null);

        assertThat(dto.isHasPhoto()).isTrue();
        assertThat(complaint.getPhotoObjectKey()).matches("photos/41/[0-9a-f-]{36}\\.jpg");
        assertThat(storage.objects).doesNotContainKey(key);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(storage.objects.get(complaint.getPhotoObjectKey())));
        assertThat(stored.getWidth()).isEqualTo(ImageNormaliser.MAX_EDGE);              // scaled down
        assertThat(stored.getHeight()).isEqualTo(1024);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                storage.objects.get(ComplaintPhotoService.thumbnailKey(complaint.getPhotoObjectKey()))));
        assertThat(thumb.getWidth()).isEqualTo(ImageNormaliser.THUMB_EDGE);

        // One photo per complaint.
        assertThatThrownBy(() -> service.intent(ID, "image/png", 100L, tokens.issue(ID), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already has a photo");
    }

    @Test
    @DisplayName("confirm refuses a non-image, a tracking pixel, and a key it did not issue — and leaves no photo")
    void confirmRefusals() throws IOException {
        String token = tokens.issue(ID);

        String notImage = upload("<svg onload=alert(1)>".getBytes());
        assertThatThrownBy(() -> service.confirm(ID, notImage, token, null)).isInstanceOf(ValidationException.class);
        assertThat(storage.objects).doesNotContainKey(notImage);               // cleaned up

        String pixel = upload(png(1, 1));
        assertThatThrownBy(() -> service.confirm(ID, pixel, token, null)).isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> service.confirm(ID, "uploads/42/" + java.util.UUID.randomUUID(), token, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("not issued");
        assertThatThrownBy(() -> service.confirm(ID, "uploads/41/../../photos/x.jpg", token, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.confirm(ID, "uploads/41/" + java.util.UUID.randomUUID(), token, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("No upload");

        assertThat(complaint.getPhotoObjectKey()).isNull();
    }

    @Test
    @DisplayName("reading: officers and the owner get signed links; anyone else a 404")
    void reading() throws IOException {
        service.confirm(ID, upload(png(200, 200)), tokens.issue(ID), null);

        assertThat(service.links(ID, 1L, true).thumbnailUrl()).contains("-thumb.jpg");
        assertThat(service.links(ID, OWNER, false).url()).endsWith(".jpg?get");
        assertThatThrownBy(() -> service.links(ID, 99L, false)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the sweep deletes only stale, unconfirmed uploads")
    void sweep() throws IOException {
        String stale = upload(png(100, 100));
        storage.modified.put(stale, Instant.now().minus(Duration.ofHours(25)));
        String fresh = upload(png(100, 100));

        assertThat(service.sweepOrphans()).isEqualTo(1);
        assertThat(storage.objects).doesNotContainKey(stale).containsKey(fresh);
    }

    @Test
    @DisplayName("with no bucket configured every step is a 503, and no token is issued")
    void disabled() {
        var off = new ComplaintPhotoService(mock(ComplaintRepository.class), PhotoStorageConfig.DISABLED, tokens,
                new AppProperties());
        assertThat(off.tokenFor(ID)).isNull();
        assertThatThrownBy(() -> off.intent(ID, "image/png", 10L, null, OWNER))
                .isInstanceOf(PhotoUnavailableException.class);
        assertThat(off.sweepOrphans()).isZero();
    }
}
