package com.example.kladdo.repository;

import com.example.kladdo.model.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * Non-archived clients only (the default list). Treats a {@code null} archived flag as not archived
     * so clients that predate the column still appear. Tenant scoping is applied automatically.
     */
    @Query("SELECT c FROM Client c WHERE c.archived = false OR c.archived IS NULL")
    Page<Client> findNotArchived(Pageable pageable);
}
