package com.example.kladdo.repository;

import com.example.kladdo.model.SentEmail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SentEmailRepository
        extends JpaRepository<SentEmail, Long>, JpaSpecificationExecutor<SentEmail> {

    /**
     * Look up a sent email by its tracking token. Used by the authenticated detail flow. The public
     * pixel/webhook endpoints must NOT use this method: they run with no tenant bound, so the tenant
     * filter would resolve to company -1 and match nothing - they resolve the token via a raw JDBC query
     * first and only then bind the tenant (see {@code TrackingService}/{@code InboundReplyService}).
     */
    Optional<SentEmail> findByTrackingToken(String trackingToken);

    /** Count of emails a user has sent since the given instant (drives the user-profile stat). */
    long countBySentByIdAndSentAtAfter(Long sentById, Instant after);

    /** Total count of emails a user has sent (all time). */
    long countBySentById(Long sentById);

    /** All recipient rows of one bulk send, oldest first. Tenant-scoped via {@code @TenantId}. */
    List<SentEmail> findByBatchIdOrderByIdAsc(String batchId);

    /**
     * Aggregated sent-email list: one row per bulk send (grouped by {@code batchId}), newest first. Rows
     * predating batching (null {@code batchId}) fall back to a synthetic per-id key so they still list as
     * singleton batches. Each result row is
     * {@code [batchKey, minId, subject, sentById, sentAt, recipients, viewed, replied, sent, failed,
     * manufacturerName, recipientEmail]} - see {@link com.example.kladdo.dto.SentEmailBatchDto#fromRow}.
     *
     * <p>{@code senderId} optionally restricts to one sender (non-managers are locked to their own). When
     * {@code search} (a pre-lowercased {@code %term%} pattern) is non-null, a batch is included if <em>any</em>
     * of its recipients matches on subject / manufacturer name / recipient email, so the aggregate counts
     * stay whole. Snapshot columns are used throughout, so this never inner-joins the nullable manufacturer.</p>
     */
    @Query(value = """
            SELECT COALESCE(e.batchId, CONCAT('legacy-', CAST(e.id AS string))),
                   MIN(e.id),
                   MAX(e.subjectSnapshot),
                   MAX(e.sentById),
                   MIN(e.sentAt),
                   COUNT(e),
                   SUM(CASE WHEN e.viewedAt IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN e.repliedAt IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN e.status = com.example.kladdo.model.SentEmailStatus.SENT THEN 1 ELSE 0 END),
                   SUM(CASE WHEN e.status = com.example.kladdo.model.SentEmailStatus.FAILED THEN 1 ELSE 0 END),
                   MAX(e.manufacturerNameSnapshot),
                   MAX(e.recipientEmail)
            FROM SentEmail e
            WHERE (:senderId IS NULL OR e.sentById = :senderId)
              AND (:search IS NULL OR COALESCE(e.batchId, CONCAT('legacy-', CAST(e.id AS string))) IN (
                    SELECT COALESCE(s.batchId, CONCAT('legacy-', CAST(s.id AS string)))
                    FROM SentEmail s
                    WHERE LOWER(s.subjectSnapshot) LIKE :search
                       OR LOWER(s.manufacturerNameSnapshot) LIKE :search
                       OR LOWER(s.recipientEmail) LIKE :search))
            GROUP BY COALESCE(e.batchId, CONCAT('legacy-', CAST(e.id AS string)))
            ORDER BY MIN(e.sentAt) DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT COALESCE(e.batchId, CONCAT('legacy-', CAST(e.id AS string))))
            FROM SentEmail e
            WHERE (:senderId IS NULL OR e.sentById = :senderId)
              AND (:search IS NULL OR COALESCE(e.batchId, CONCAT('legacy-', CAST(e.id AS string))) IN (
                    SELECT COALESCE(s.batchId, CONCAT('legacy-', CAST(s.id AS string)))
                    FROM SentEmail s
                    WHERE LOWER(s.subjectSnapshot) LIKE :search
                       OR LOWER(s.manufacturerNameSnapshot) LIKE :search
                       OR LOWER(s.recipientEmail) LIKE :search))
            """)
    Page<Object[]> findBatches(@Param("senderId") Long senderId, @Param("search") String search, Pageable pageable);
}
