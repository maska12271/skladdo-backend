package com.example.kladdo.service;

import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Category;
import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.TaxRate;
import com.example.kladdo.model.WarehouseMethod;
import com.example.kladdo.repository.ProductBatchRepository;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.TaxRateRepository;
import com.example.kladdo.repository.WarehouseStockRepository;
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
    private final TaxRateRepository taxRateRepository;
    private final CompanySettingsService companySettingsService;
    private final WarehouseStockRepository warehouseStockRepository;
    private final ProductBatchRepository productBatchRepository;

    public ProductService(ProductRepository productRepository,
                          CategoryService categoryService,
                          ManufacturerService manufacturerService,
                          TaxRateRepository taxRateRepository,
                          CompanySettingsService companySettingsService,
                          WarehouseStockRepository warehouseStockRepository,
                          ProductBatchRepository productBatchRepository) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.manufacturerService = manufacturerService;
        this.taxRateRepository = taxRateRepository;
        this.companySettingsService = companySettingsService;
        this.warehouseStockRepository = warehouseStockRepository;
        this.productBatchRepository = productBatchRepository;
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
        // A brand-new product (no id yet) picks up the company's new-product defaults; existing
        // products being re-saved (e.g. order stock adjustments) are left untouched.
        if (product.getId() == null) {
            applyNewProductDefaults(product);
        }
        // A blank SKU must be stored as NULL, not "" — the column is unique and many products have no
        // SKU (multiple NULLs are allowed, multiple ""s collide).
        product.setSku(blankToNull(product.getSku()));
        resolveTaxRate(product);
        return productRepository.save(product);
    }

    @Transactional
    public Product create(Product product) {
        Category category = categoryService.findById(product.getCategory().getId());
        Manufacturer manufacturer = manufacturerService.findById(product.getManufacturer().getId());

        product.setCategory(category);
        product.setManufacturer(manufacturer);

        applyNewProductDefaults(product);
        resolveTaxRate(product);

        return productRepository.save(product);
    }

    /**
     * Fills in the company-configured defaults for fields the caller left blank: unit, minimum stock,
     * stock quantity and - when no rate was chosen - the company's default tax rate.
     */
    private void applyNewProductDefaults(Product product) {
        CompanySettings settings = companySettingsService.getOrCreate();

        if (product.getUnit() == null || product.getUnit().isBlank()) {
            product.setUnit(settings.getDefaultProductUnit());
        }
        if (product.getMinimumStock() == null) {
            product.setMinimumStock(settings.getDefaultMinimumStock());
        }
        if (product.getStockQuantity() == null) {
            product.setStockQuantity(0);
        }
        if (product.getTaxRate() == null) {
            taxRateRepository.findFirstByIsDefaultTrue().ifPresent(product::setTaxRate);
        }
    }

    /**
     * Replaces a client-supplied tax-rate reference with the managed, tenant-scoped entity so a product
     * can never be linked to another company's rate. A {@code null} rate is left as-is.
     */
    private void resolveTaxRate(Product product) {
        TaxRate rate = product.getTaxRate();
        if (rate != null && rate.getId() != null) {
            TaxRate managed = taxRateRepository.findById(rate.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Tax rate not found with id: " + rate.getId()));
            product.setTaxRate(managed);
        } else {
            product.setTaxRate(null);
        }
    }

    @Transactional
    public Product update(Long id, Product updatedProduct) {
        Product product = findById(id);

        Category category = categoryService.findById(updatedProduct.getCategory().getId());
        Manufacturer manufacturer = manufacturerService.findById(updatedProduct.getManufacturer().getId());

        product.setName(updatedProduct.getName());
        product.setSku(blankToNull(updatedProduct.getSku()));
        product.setManufacturer(manufacturer);
        product.setCategory(category);
        product.setSize(updatedProduct.getSize());
        product.setUnit(updatedProduct.getUnit());
        product.setDescription(updatedProduct.getDescription());
        product.setImageUrls(updatedProduct.getImageUrls() == null ? new ArrayList<>() : updatedProduct.getImageUrls());
        product.setPrice(updatedProduct.getPrice());
        // stockQuantity is managed by warehouse operations — do not accept it from the product edit form.
        product.setMinimumStock(updatedProduct.getMinimumStock() == null ? 0 : updatedProduct.getMinimumStock());
        product.setWarehouseMethod(updatedProduct.getWarehouseMethod() == null
                ? WarehouseMethod.FEFO : updatedProduct.getWarehouseMethod());
        product.setActive(updatedProduct.getActive());

        product.setTaxRate(updatedProduct.getTaxRate());
        resolveTaxRate(product);

        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        warehouseStockRepository.deleteByProductId(id);
        productBatchRepository.deleteByProductId(id);
        productRepository.delete(findById(id));
    }

    /** Trims a string and returns null when it is null or blank (so the unique SKU stores NULL, not ""). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}