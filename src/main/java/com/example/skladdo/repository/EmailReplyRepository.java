package com.example.skladdo.repository;

import com.example.skladdo.model.EmailReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailReplyRepository extends JpaRepository<EmailReply, Long> {

    /** Replies to a sent email, oldest first (for the reply thread on the detail view). */
    List<EmailReply> findBySentEmailIdOrderByReceivedAtAsc(Long sentEmailId);
}
