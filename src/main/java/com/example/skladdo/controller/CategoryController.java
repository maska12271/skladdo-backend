package com.example.skladdo.controller;

import com.example.skladdo.model.Category;
import com.example.skladdo.service.CategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'CATEGORIES')")
    public Page<Category> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return categoryService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'CATEGORIES')")
    public Category getById(@PathVariable Long id) {
        return categoryService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'CATEGORIES')")
    public Category create(@Valid @RequestBody Category category) {
        return categoryService.save(category);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'CATEGORIES')")
    public Category update(@PathVariable Long id, @Valid @RequestBody Category category) {
        return categoryService.update(id, category);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'CATEGORIES')")
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }
}