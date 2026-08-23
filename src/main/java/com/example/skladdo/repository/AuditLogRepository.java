package com.example.skladdo.repository;

import com.example.skladdo.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Audit rows for the calling company ({@code @TenantId} scopes every query automatically).
 * {@link JpaSpecificationExecutor} backs the filtered, paged viewer - the same approach the other
 * server-paginated lists use.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /** Distinct actors that appear in this company's trail, for the viewer's "who" filter. */
    @org.springframework.data.jpa.repository.Query(
            "select distinct a.actorUserId, a.actorName from AuditLog a where a.actorUserId is not null order by a.actorName")
    java.util.List<Object[]> findDistinctActors();
}
