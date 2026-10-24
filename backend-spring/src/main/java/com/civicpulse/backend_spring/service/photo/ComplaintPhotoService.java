package com.civicpulse.backend_spring.service.photo;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.exception.PhotoUnavailableException;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One photo per complaint (docs/photo-upload-contract.md):
 *
 * <ol>
 *   <li>{@link #intent} — permission to upload: a presigned PUT for exactly
 *       the declared type and size, to a key the server chose.</li>
 *   <li>The browser PUTs to storage directly; image bytes never hold a
 *       request thread here.</li>
 *   <li>{@link #confirm} — the server reads what actually arrived, decodes
 *       it, re-encodes it without metadata ({@link ImageNormaliser}), stores
 *       the result and its thumbnail, deletes the raw upload, and only then
 *       writes the key to the complaint.</li>
 * </ol>
 *
 * <p>Who may upload: the officer never does; a signed-in citizen for their own
 * complaint; anyone holding the complaint's photo-upload token (issued once,
 * on the create response). Who may read: officers, and the owning citizen.
 * Everyone else gets a 404, as on the complaint itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintPhotoService {

    static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final String UPLOAD_PREFIX = "uploads/";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ComplaintRepository complaintRepository;
    private final PhotoStorage storage;
    private final PhotoUploadTokens tokens;
    private final AppProperties appProperties;

    public record Intent(String uploadUrl, String objectKey, String expiresAt, long maxBytes, String contentType) {
    }

    public record PhotoLinks(String url, String thumbnailUrl, String expiresAt) {
    }

    public boolean isEnabled() {
        return storage.isEnabled();
    }

    /** The token for the create response, or null when photos are off. */
    public String tokenFor(long complaintId) {
        return storage.isEnabled() ? tokens.issue(complaintId) : null;
    }

    @Transactional(readOnly = true)
    public Intent intent(long complaintId, String contentType, Long sizeBytes, String token, Long userId) {
        requireEnabled();
        Complaint complaint = requireUploader(complaintId, token, userId);
        refuseIfAttached(complaint);

        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(type)) {
            throw new ValidationException("contentType must be one of image/jpeg, image/png, image/webp");
        }
        long max = config().getMaxBytes();
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > max) {
            throw new ValidationException("sizeBytes must be between 1 and " + max);
        }

        // Server-chosen key: never the phone's file name (traversal, overwrite,
        // and it leaks whatever the resident called the file).
        String key = UPLOAD_PREFIX + complaintId + "/" + UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(config().getIntentMinutes());
        String url = storage.presignPut(key, type, sizeBytes, ttl);
        return new Intent(url, key, Instant.now().plus(ttl).toString(), max, type);
    }

    @Transactional
    public ComplaintDto confirm(long complaintId, String objectKey, String token, Long userId) {
        requireEnabled();
        Complaint complaint = requireUploader(complaintId, token, userId);
        refuseIfAttached(complaint);

        // Only a key this server could have issued for THIS complaint.
        String prefix = UPLOAD_PREFIX + complaintId + "/";
        if (objectKey == null || !objectKey.startsWith(prefix)
                || !UUID_PATTERN.matcher(objectKey.substring(prefix.length())).matches()) {
            throw new ValidationException("objectKey was not issued for this complaint");
        }

        long max = config().getMaxBytes();
        PhotoStorage.ObjectInfo info = storage.head(objectKey)
                .orElseThrow(() -> new ValidationException("No upload found for objectKey; upload it first"));
        if (info.sizeBytes() > max || info.sizeBytes() <= 0) {
            storage.delete(objectKey);
            throw new ValidationException("The upload is larger than " + max + " bytes");
        }

        ImageNormaliser.Normalised image = ImageNormaliser.normalise(storage.get(objectKey, max)).orElse(null);
        if (image == null) {
            storage.delete(objectKey);
            throw new ValidationException(
                    "The upload is not a JPEG, PNG or WebP photo of at least " + ImageNormaliser.MIN_EDGE + " px");
        }

        String base = "photos/" + complaintId + "/" + UUID.randomUUID();
        storage.put(base + ".jpg", image.photo(), "image/jpeg");
        storage.put(thumbnailKey(base + ".jpg"), image.thumbnail(), "image/jpeg");
        storage.delete(objectKey);

        complaint.setPhotoObjectKey(base + ".jpg");
        complaint.setPhotoAttachedAt(LocalDateTime.now());
        Complaint saved = complaintRepository.save(complaint);
        log.info("Photo attached to complaint {} ({}x{}, {} bytes stored)", complaintId,
                image.width(), image.height(), image.photo().length);
        return ComplaintDto.from(saved);
    }

    /** Short-lived signed links for the officer or the owning citizen; 404 for anyone else. */
    @Transactional(readOnly = true)
    public PhotoLinks links(long complaintId, Long userId, boolean officer) {
        requireEnabled();
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
        if (!officer && !isOwner(complaint, userId)) {
            throw new ResourceNotFoundException("Complaint not found");
        }
        if (complaint.getPhotoObjectKey() == null) {
            throw new ResourceNotFoundException("This complaint has no photo");
        }
        Duration ttl = Duration.ofMinutes(config().getReadMinutes());
        return new PhotoLinks(
                storage.presignGet(complaint.getPhotoObjectKey(), ttl),
                storage.presignGet(thumbnailKey(complaint.getPhotoObjectKey()), ttl),
                Instant.now().plus(ttl).toString());
    }

    /** Deletes uploads whose intent was issued but never confirmed. */
    public int sweepOrphans() {
        if (!storage.isEnabled()) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofHours(config().getOrphanHours()));
        int deleted = 0;
        for (String key : storage.listOlderThan(UPLOAD_PREFIX, cutoff)) {
            storage.delete(key);
            deleted++;
        }
        return deleted;
    }

    static String thumbnailKey(String photoKey) {
        return photoKey.substring(0, photoKey.length() - ".jpg".length()) + "-thumb.jpg";
    }

    private Complaint requireUploader(long complaintId, String token, Long userId) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
        // 404 rather than 403 for a wrong token too: a distinct answer would
        // confirm the complaint exists.
        if (!isOwner(complaint, userId) && !tokens.verify(token, complaintId)) {
            throw new ResourceNotFoundException("Complaint not found");
        }
        return complaint;
    }

    private static boolean isOwner(Complaint complaint, Long userId) {
        return userId != null && complaint.getUser() != null && userId.equals(complaint.getUser().getId());
    }

    private static void refuseIfAttached(Complaint complaint) {
        if (complaint.getPhotoObjectKey() != null) {
            throw new ValidationException("This complaint already has a photo (one per complaint)");
        }
    }

    private void requireEnabled() {
        if (!storage.isEnabled()) throw new PhotoUnavailableException();
    }

    private AppProperties.Photos config() {
        return appProperties.getPhotos();
    }
}
