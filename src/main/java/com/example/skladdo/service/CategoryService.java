package com.example.skladdo.service;

import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Category;
import com.example.skladdo.repository.CategoryRepository;
import com.example.skladdo.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
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

    /**
     * Deletes a category, un-filing any products in it first.
     *
     * <p>Deleting used to fail outright once a single product referenced the category - the database
     * refused the foreign key, and the user was told the action "conflicts with existing data" with no way
     * forward short of re-filing every product by hand. A category is a label, not the thing itself, so
     * removing it keeps the products and just leaves them uncategorised.</p>
     *
     * <p>Transactional so the un-filing and the delete cannot come apart: a failure after the update would
     * otherwise leave every product stripped of a category that still exists.</p>
     */
    @Transactional
    public void delete(Long id) {
        Category category = findById(id);
        productRepository.clearCategory(id);
        categoryRepository.delete(category);
    }
}