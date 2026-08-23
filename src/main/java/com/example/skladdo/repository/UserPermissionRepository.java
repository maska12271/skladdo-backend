package com.example.skladdo.repository;

import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    List<UserPermission> findByUserId(Long userId);

    Optional<UserPermission> findByUserIdAndModule(Long userId, PermissionModule module);

    void deleteByUserId(Long userId);
}
