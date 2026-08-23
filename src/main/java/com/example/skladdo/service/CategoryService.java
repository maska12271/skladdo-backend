package com.example.skladdo.service;

import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Category;
import com.example.skladdo.repository.CategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Page<Category> findAll(Pageable pageable) {
        return categoryRepository.findAll(pageable);
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
    }

    public Category save(Category category) {
        return categoryRepository.save(category);
    }

    public Category update(Long id, Category updatedCategory) {
        Category category = findById(id);
        category.setName(updatedCategory.getName());
        category.setDescription(updatedCategory.getDescription());
        category.setActive(updatedCategory.getActive());
        return categoryRepository.save(category);
    }

    public void delete(Long id) {
        categoryRepository.delete(findById(id));
    }
}