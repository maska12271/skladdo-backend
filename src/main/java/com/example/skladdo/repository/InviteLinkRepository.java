package com.example.skladdo.repository;

import com.example.skladdo.model.InviteLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InviteLinkRepository extends JpaRepository<InviteLink, Long> {

    Optional<InviteLink> findByCode(String code);

    List<InviteLink> findAllByOrderByIdDesc();
}
