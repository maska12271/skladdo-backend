package com.example.skladdo.service;

import com.example.skladdo.dto.EmailTemplateDto;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.EmailTemplate;
import com.example.skladdo.repository.EmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for the calling company's reusable {@link EmailTemplate}s. Tenant-scoped automatically via the
 * entity's {@code @TenantId}. Deleting a template does not touch already-sent emails - those keep their
 * own snapshots of the rendered content.
 */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> findAll() {
        return repository.findAll().stream().map(EmailTemplateDto::from).toList();
    }

    @Transactional
    public EmailTemplateDto create(EmailTemplateDto dto) {
        EmailTemplate template = new EmailTemplate();
        apply(dto, template);
        return EmailTemplateDto.from(repository.save(template));
    }

    @Transactional
    public EmailTemplateDto update(Long id, EmailTemplateDto dto) {
        EmailTemplate template = require(id);
        apply(dto, template);
        return EmailTemplateDto.from(repository.save(template));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    private void apply(EmailTemplateDto dto, EmailTemplate template) {
        template.setName(dto.name());
        template.setSubject(dto.subject());
        template.setBody(dto.body());
        template.setActive(dto.activeOrDefault());
    }

    private EmailTemplate require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email template not found with id: " + id));
    }
}
