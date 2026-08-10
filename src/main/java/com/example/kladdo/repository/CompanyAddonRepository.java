package com.example.kladdo.repository;

import com.example.kladdo.model.AddonType;
import com.example.kladdo.model.CompanyAddon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyAddonRepository extends JpaRepository<CompanyAddon, Long> {

    /** The calling company's row for one add-on, if it has ever activated it (the {@code @TenantId} filter scopes it). */
    Optional<CompanyAddon> findByAddonType(AddonType addonType);
}
