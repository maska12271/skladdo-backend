package com.example.kladdo.repository;

import com.example.kladdo.model.TenderParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderParticipantRepository extends JpaRepository<TenderParticipant, Long> {
    List<TenderParticipant> findByTenderId(Long tenderId);
}