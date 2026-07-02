package com.example.kladdo.service;

import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.PartnerCategory;
import com.example.kladdo.repository.PartnerCategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class PartnerCategoryService {

    private final PartnerCategoryRepository partnerCategoryRepository;

    public PartnerCategoryService(PartnerCategoryRepository partnerCategoryRepository) {
        this.partnerCategoryRepository = partnerCategoryRepository;
    }

    public Page<PartnerCategory> findAll(Pageable pageable) {
        return partnerCategoryRepository.findAll(pageable);
    }

    public PartnerCategory findById(Long id) {
        return partnerCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Partner category not found with id: " + id));
    }

    public PartnerCategory save(PartnerCategory partnerCategory) {
        return partnerCategoryRepository.save(partnerCategory);
    }

    public PartnerCategory update(Long id, PartnerCategory updatedCategory) {
        PartnerCategory category = findById(id);
        category.setName(updatedCategory.getName());
        category.setDescription(updatedCategory.getDescription());
        category.setActive(updatedCategory.getActive());
        return partnerCategoryRepository.save(category);
    }

    public void delete(Long id) {
        partnerCategoryRepository.delete(findById(id));
    }
}
