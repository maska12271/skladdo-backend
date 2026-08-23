package com.example.skladdo.repository;

import com.example.skladdo.model.TenderParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenderParticipantRepository extends JpaRepository<TenderParticipant, Long> {

    List<TenderParticipant> findByTenderId(Long tenderId);

    /** A part's participants, our own company first, then by insertion order. */
    List<TenderParticipant> findByPartIdOrderByOwnCompanyDescIdAsc(Long partId);

    /** Legacy participants that predate parts (attached to a tender but not yet to a part). */
    List<TenderParticipant> findByTenderIdAndPartIsNull(Long tenderId);

    boolean existsByPartIdAndOwnCompanyTrue(Long partId);
}
