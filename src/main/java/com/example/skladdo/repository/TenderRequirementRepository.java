package com.example.skladdo.repository;

import com.example.skladdo.model.TenderRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderRequirementRepository extends JpaRepository<TenderRequirement, Long> {

    List<TenderRequirement> findByPartIdOrderBySortOrderAscIdAsc(Long partId);
}
