package com.example.kladdo.repository;

import com.example.kladdo.model.Tender;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface TenderRepository extends JpaRepository<Tender, Long>, JpaSpecificationExecutor<Tender> {

    @Override
    @EntityGraph(attributePaths = {"client"})
    Page<Tender> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"client"})
    Page<Tender> findAll(Specification<Tender> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"client"})
    Optional<Tender> findById(Long id);
}
