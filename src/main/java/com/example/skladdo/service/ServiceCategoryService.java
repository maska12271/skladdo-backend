package com.example.skladdo.service;

import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.ServiceCategory;
import com.example.skladdo.repository.ServiceCategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ServiceCategoryService {

    private final ServiceCategoryRepository serviceCategoryRepository;

    public ServiceCategoryService(ServiceCategoryRepository serviceCategoryRepository) {
        this.serviceCategoryRepository = serviceCategoryRepository;
    }

    public Page<ServiceCategory> findAll(Pageable pageable) {
        return serviceCategoryRepository.findAll(pageable);
    }

    public ServiceCategory findById(Long id) {
        return serviceCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service category not found with id: " + id));
    }

    public ServiceCategory save(ServiceCategory category) {
        return serviceCategoryRepository.save(category);
    }

    public ServiceCategory update(Long id, ServiceCategory updatedCategory) {
        ServiceCategory category = findById(id);
        category.setName(updatedCategory.getName());
        category.setDescription(updatedCategory.getDescription());
        category.setActive(updatedCategory.getActive());
        return serviceCategoryRepository.save(category);
    }

    public void delete(Long id) {
        serviceCategoryRepository.delete(findById(id));
    }
}
