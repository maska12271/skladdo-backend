package com.example.skladdo.repository;

import com.example.skladdo.model.TenderPart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderPartRepository extends JpaRepository<TenderPart, Long> {

    List<TenderPart> findByTenderIdOrderBySortOrderAscIdAsc(Long tenderId);

    long countByTenderId(Long tenderId);
}
