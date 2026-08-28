package com.example.skladdo.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;

/**
 * Object storage for uploads (product images, company logo, PO invoice documents). Callers store
 * and reference files purely by key; a key is never a fetchable URL on its own — {@link #presign}
 * mints a short-lived URL on demand, and no stored key/DB column should ever hold a presigned URL
 * (they expire, and would silently break the reference if persisted).
 */
public interface StorageService {

    /** Stores the file under a random key namespaced by {@code category} (e.g. "images", "documents"). */
    String store(MultipartFile file, String category);

    /**
     * Stores raw bytes under a random key, for content that never arrived as an upload.
     *
     * <p>Used by invitation redemption, which is unauthenticated and therefore has no business exposing an
     * upload endpoint: the invitee's photo travels inside the (token-gated) accept request instead, as
     * bytes rather than a multipart part.</p>
     */
    String store(byte[] bytes, String contentType, String extension, String category);

    /** A fetchable, time-limited GET URL for the object at {@code key}. */
    String presign(String key, Duration ttl);

    /** Streams the object's bytes directly, for server-side proxying (e.g. an authenticated download). */
    StoredObject open(String key);

    /**
     * Permanently removes an object.
     *
     * <p>Idempotent: deleting a key that is not there is not an error, which matters because the caller is
     * usually reconciling a list and cannot be certain the object still exists.</p>
     *
     * <p>Note this only reclaims space on a bucket <em>without</em> versioning. With versioning enabled a
     * delete without a version id just writes a delete marker and the previous version is retained - and
     * still billed. The buckets this app uses have versioning disabled; if that changes, this needs to
     * delete versions or the bucket needs a lifecycle rule to expire noncurrent ones.</p>
     */
    void delete(String key);

    /** An open object stream plus the content type recorded when it was stored. */
    record StoredObject(InputStream content, String contentType) {
    }
}
