package com.example.tenderapp.service;

import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Category;
import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.repository.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ManufacturerService manufacturerService;

    public ProductService(ProductRepository productRepository,
                          CategoryService categoryService,
                          ManufacturerService manufacturerService) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.manufacturerService = manufacturerService;
    }

    public Page<Product> search(String name, Long categoryId, Long manufacturerId, Boolean active, Pageable pageable) {
        Specification<Product> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (name != null && !name.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%"));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (manufacturerId != null) {
                predicates.add(cb.equal(root.get("manufacturer").get("id"), manufacturerId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return productRepository.findAll(specification, pageable);
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }

    @Transactional
    public Product save(Product product) {
        return productRepository.save(product);
    }

    @Transactional
    public Product create(Product product) {
        Category category = categoryService.findById(product.getCategory().getId());
        Manufacturer manufacturer = manufacturerService.findById(product.getManufacturer().getId());

        product.setCategory(category);
        product.setManufacturer(manufacturer);

        if (product.getStockQuantity() == null) {
            product.setStockQuantity(0);
        }
        if (product.getMinimumStock() == null) {
            product.setMinimumStock(0);
        }

        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, Product updatedProduct) {
        Product product = findById(id);

        Category category = categoryService.findById(updatedProduct.getCategory().getId());
        Manufacturer manufacturer = manufacturerService.findById(updatedProduct.getManufacturer().getId());

        product.setName(updatedProduct.getName());
        product.setSku(updatedProduct.getSku());
        product.setManufacturer(manufacturer);
        product.setCategory(category);
        product.setSize(updatedProduct.getSize());
        product.setUnit(updatedProduct.getUnit());
        product.setDescription(updatedProduct.getDescription());
        product.setImageUrl(updatedProduct.getImageUrl());
        product.setPrice(updatedProduct.getPrice());
        product.setStockQuantity(updatedProduct.getStockQuantity() == null ? 0 : updatedProduct.getStockQuantity());
        product.setMinimumStock(updatedProduct.getMinimumStock() == null ? 0 : updatedProduct.getMinimumStock());
        product.setActive(updatedProduct.getActive());

        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(findById(id));
    }
}