package com.example.kladdo.repository;

import com.example.kladdo.model.TenderPart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderPartRepository extends JpaRepository<TenderPart, Long> {

    List<TenderPart> findByTenderIdOrderBySortOrderAscIdAsc(Long tenderId);

    long countByTenderId(Long tenderId);
}
