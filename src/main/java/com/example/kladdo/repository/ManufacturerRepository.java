package com.example.kladdo.repository;

import com.example.kladdo.model.Manufacturer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long>, JpaSpecificationExecutor<Manufacturer> {

    /** Distinct non-empty countries used by this tenant's manufacturers, most-used first. */
    @Query("SELECT m.country FROM Manufacturer m WHERE m.country IS NOT NULL AND m.country <> '' " +
            "GROUP BY m.country ORDER BY COUNT(m) DESC, m.country ASC")
    List<String> findCountriesByUsage();
}