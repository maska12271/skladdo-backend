package com.example.tenderapp.repository;

import com.example.tenderapp.model.Tender;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenderRepository extends JpaRepository<Tender, Long> {

    @Override
    @EntityGraph(attributePaths = {"client"})
    Page<Tender> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"client"})
    Optional<Tender> findById(Long id);
}