package com.example.kladdo.repository;

import com.example.kladdo.model.TenderRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderRequirementRepository extends JpaRepository<TenderRequirement, Long> {

    List<TenderRequirement> findByPartIdOrderBySortOrderAscIdAsc(Long partId);
}
