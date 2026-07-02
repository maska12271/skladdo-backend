package com.example.kladdo.repository;

import com.example.kladdo.model.DefaultUserPermission;
import com.example.kladdo.model.PermissionModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DefaultUserPermissionRepository extends JpaRepository<DefaultUserPermission, Long> {

    /** The current company's default-permission template (scoped by {@code @TenantId}). */
    List<DefaultUserPermission> findAll();

    Optional<DefaultUserPermission> findByModule(PermissionModule module);
}
