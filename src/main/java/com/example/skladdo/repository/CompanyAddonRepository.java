package com.example.skladdo.repository;

import com.example.skladdo.model.AddonType;
import com.example.skladdo.model.CompanyAddon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyAddonRepository extends JpaRepository<CompanyAddon, Long> {

    /** The calling company's row for one add-on, if it has ever activated it (the {@code @TenantId} filter scopes it). */
    Optional<CompanyAddon> findByAddonType(AddonType addonType);
}
