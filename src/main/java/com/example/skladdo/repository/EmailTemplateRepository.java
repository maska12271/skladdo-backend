package com.example.skladdo.repository;

import com.example.skladdo.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    /** Active templates only (company-scoped automatically via the tenant filter). */
    List<EmailTemplate> findByActiveTrueOrderByNameAsc();
}
