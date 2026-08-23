package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * One object in the bucket, and which company put it there.
 *
 * <p>The bucket alone cannot answer either question we need of it. Keys are {@code images/<uuid>.png} -
 * flat and tenant-free - so "how much is this account using" is not derivable from a listing, and nothing
 * records that an object exists at all, so an image detached from a product simply stays there forever.
 * This table is the index that makes both answerable: usage is a sum over rows, and an orphan is a row
 * whose key nothing references.</p>
 *
 * <p>Rows are written when an upload succeeds. Objects predating this table have no row, so usage counts
 * from here forward - see {@code AdminController#storageUsage}.</p>
 */
@Entity
@Table(indexes = {
        // Usage is grouped by company; the key lookup is how a delete finds its row.
        @Index(name = "idx_stored_file_company", columnList = "company_id"),
        @Index(name = "idx_stored_file_key", columnList = "storageKey", unique = true)
})
@Getter
@Setter
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    /** The object key as stored, e.g. {@code images/3295ef6c-....png}. */
    @Column(nullable = false, updatable = false, length = 512)
    private String storageKey;

    /** Wrapper rather than a primitive, per the schema-less migration convention. */
    @Column(updatable = false)
    private Long sizeBytes;

    @Column(updatable = false, length = 128)
    private String contentType;

    /** The folder the key sits under: {@code images} or {@code documents}. */
    @Column(updatable = false, length = 32)
    private String category;

    /** What the user called it, for an admin looking at a list of otherwise opaque uuids. */
    @Column(updatable = false, length = 255)
    private String originalName;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public long getSizeBytesOrZero() {
        return sizeBytes == null ? 0L : sizeBytes;
    }
}
