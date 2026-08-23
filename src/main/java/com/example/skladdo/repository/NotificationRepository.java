package com.example.skladdo.repository;

import com.example.skladdo.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Notifications for the calling company ({@code @TenantId}), always further filtered by recipient. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    List<Notification> findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    /** Dedupe guard: has this user already been told about this exact condition? */
    boolean existsByRecipientUserIdAndDedupeKey(Long recipientUserId, String dedupeKey);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientUserId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
