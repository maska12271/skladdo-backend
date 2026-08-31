package com.example.skladdo.repository;

import com.example.skladdo.model.ScheduledEmail;
import com.example.skladdo.model.ScheduledEmailStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Tenant-scoped through {@link ScheduledEmail}'s {@code @TenantId}, so no company argument is needed. */
public interface ScheduledEmailRepository extends JpaRepository<ScheduledEmail, Long> {

    /**
     * Everything still outstanding or stuck, soonest first - which is the whole table, since a schedule
     * that fires or is cancelled is deleted.
     */
    List<ScheduledEmail> findAllByOrderByScheduledAtAsc();

    /** Same, restricted to one author (what a non-manager is allowed to see). */
    List<ScheduledEmail> findByCreatedByIdOrderByScheduledAtAsc(Long createdById);

    /** Ids the dispatcher should pick up: due, and not already claimed by an earlier run. */
    List<ScheduledEmail> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledEmailStatus status, Instant cutoff);
}
