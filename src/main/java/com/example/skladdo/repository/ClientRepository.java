package com.example.skladdo.repository;

import com.example.skladdo.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, Long>, JpaSpecificationExecutor<Client> {

    /** Distinct non-empty countries used by this tenant's clients, most-used first. */
    @Query("SELECT c.country FROM Client c WHERE c.country IS NOT NULL AND c.country <> '' " +
            "GROUP BY c.country ORDER BY COUNT(c) DESC, c.country ASC")
    List<String> findCountriesByUsage();
}
