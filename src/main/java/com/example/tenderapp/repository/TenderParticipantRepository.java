package com.example.tenderapp.repository;

import com.example.tenderapp.model.TenderParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderParticipantRepository extends JpaRepository<TenderParticipant, Long> {
    List<TenderParticipant> findByTenderId(Long tenderId);
}