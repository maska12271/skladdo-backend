package com.example.skladdo.service;

import com.example.skladdo.model.AuditAction;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Category;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Manufacturer;
import com.example.skladdo.model.Product;
import com.example.skladdo.model.TaxRate;
import com.example.skladdo.model.WarehouseMethod;
import com.example.skladdo.repository.ProductBatchRepository;
import com.example.skladdo.repository.ProductRepository;
import com.example.skladdo.repository.StoredFileRepository;
import com.example.skladdo.repository.TaxRateRepository;
import com.example.skladdo.repository.WarehouseStockRepository;
import jakarta.persistence.criteria.Path;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ManufacturerService manufacturerService;
    private final TaxRateRepository taxRateRepository;
    private final CompanySettingsService companySettingsService;
    private final WarehouseStockRepository warehouseStockRepository;
    private final ProductBatchRepository productBatchRepository;
    private final AuditService auditService;
    private final StorageService storageService;
    private final StoredFileRepository storedFiles;

    public ProductService(ProductRepository productRepository,
                          CategoryService categoryService,
                          ManufacturerService manufacturerService,
                          TaxRateRepository taxRateRepository,
                          CompanySettingsService companySettingsService,
                          WarehouseStockRepository warehouseStockRepository,
                          ProductBatchRepository productBatchRepository,
                          AuditService auditService,
                          StorageService storageService,
                          StoredFileRepository storedFiles) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.manufacturerService = manufacturerService;
        this.taxRateRepository = taxRateRepository;
        this.companySettingsService = companySettingsService;
        this.warehouseStockRepository = warehouseStockRepository;
        this.productBatchRepository = productBatchRepository;
        this.auditService = auditService;
        this.storageService = storageService;
        this.storedFiles = storedFiles;
    }

    /**
     * Paged product search with full filter parity for the list page: free-text search across
     * name / SKU / manufacturer name, multi-select category and manufacturer, active flag, and stock
     * level. Any argument that is null/empty is simply not applied.
     *
     * <p>The stock levels are {@code out} (qty&le;0), {@code below} (qty&lt;min) and {@code ok}
     * (qty&gt;min). {@code below} is deliberately the <em>same</em> predicate as
     * {@link com.example.skladdo.repository.ProductRepository#findLowStock()}, {@code ReorderService} and
     * the dashboard's low-stock figure, so that clicking a count of 7 lands on exactly those 7 rows. It
     * replaced a narrower "low" that required {@code qty&gt;0}: a product that has run out entirely is the
     * worst case of being below its minimum, and excluding it hid precisely the rows that mattered most.
     * {@code out} overlaps it on purpose - "show me the empties" is a different question from "show me
     * what needs reordering", and a product with no minimum set is only ever the former.</p>
     */
    public Page<Product> search(String search, List<Long> categoryIds, List<Long> manufacturerIds,
                                Boolean active, List<String> stockStatuses, Pageable pageable) {
        Specification<Product> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("sku")), like),
                        cb.like(cb.lower(root.get("manufacturer").get("name")), like)
                ));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                predicates.add(root.get("category").get("id").in(categoryIds));
            }
            if (manufacturerIds != null && !manufacturerIds.isEmpty()) {
                predicates.add(root.get("manufacturer").get("id").in(manufacturerIds));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (stockStatuses != null && !stockStatuses.isEmpty()) {
                Path<Integer> qty = root.get("stockQuantity");
                Path<Integer> min = root.get("minimumStock");
                List<Predicate> stockPredicates = new ArrayList<>();
                for (String status : stockStatuses) {
                    if ("out".equals(status)) {
                        stockPredicates.add(cb.lessThanOrEqualTo(qty, 0));
                    } else if ("below".equals(status)) {
                        // The one "running out" rule the whole application shares - see the method note.
                        // Nulls are coalesced because both columns are nullable on older rows.
                        stockPredicates.add(cb.lessThan(
                                cb.coalesce(qty, 0), cb.coalesce(min, 0)));
                    } else if ("ok".equals(status)) {
                        stockPredicates.add(cb.greaterThan(qty, min));
                    }
                }
                if (!stockPredicates.isEmpty()) {
                    predicates.add(cb.or(stockPredicates.toArray(new Predicate[0])));
                }
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
        // A brand-new product (no id yet) picks up the company's new-product defaults; existing products
        // being re-saved (e.g. order stock adjustments) are untouched.
        if (product.getId() == null) {
            applyNewProductDefaults(product);
        }
        // A blank SKU must be stored as NULL, not "" — the column is unique and many products have no
        // SKU (multiple NULLs are allowed, multiple ""s collide).
        product.setSku(blankToNull(product.getSku()));
        resolveTaxRate(product);
        boolean isNew = product.getId() == null;
        Product saved = productRepository.save(product);
        // Only audit genuinely new products: this method is also the re-save path for order stock
        // adjustments, which would otherwise flood the trail with meaningless "product updated" lines.
        if (isNew) {
            auditService.record(AuditService.ENTITY_PRODUCT, saved.getId(), AuditAction.CREATE, saved.getName());
        }
        return saved;
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
        // A product with no explicit currency is priced in the company base currency.
        if (product.getCurrency() == null || product.getCurrency().isBlank()) {
            product.setCurrency(settings.getCurrency());
        } else {
            product.setCurrency(product.getCurrency().trim().toUpperCase());
        }
    }

    /**
     * The currency to store on an edited product: the requested code when supplied, otherwise the value
     * already on the product, falling back to the company base currency.
     */
    private String resolveCurrency(String requested, String existing) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return companySettingsService.getOrCreate().getCurrency();
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
        product.setImageKeys(updatedProduct.getImageKeys() == null ? new ArrayList<>() : updatedProduct.getImageKeys());
        product.setPrice(updatedProduct.getPrice());
        product.setCurrency(resolveCurrency(updatedProduct.getCurrency(), product.getCurrency()));
        // stockQuantity is managed by warehouse operations — do not accept it from the product edit form.
        product.setMinimumStock(updatedProduct.getMinimumStock() == null ? 0 : updatedProduct.getMinimumStock());
        // Null is meaningful here (no fixed batch size), so it is copied through as-is.
        product.setReorderQuantity(updatedProduct.getReorderQuantity());
        product.setWarehouseMethod(updatedProduct.getWarehouseMethod() == null
                ? WarehouseMethod.FEFO : updatedProduct.getWarehouseMethod());
        product.setActive(updatedProduct.getActive());

        product.setTaxRate(updatedProduct.getTaxRate());
        resolveTaxRate(product);

        Product saved = productRepository.save(product);
        auditService.record(AuditService.ENTITY_PRODUCT, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    /** Replaces a product's image list, leaving every other field alone. */
    @Transactional
    public Product updateImages(Long id, List<String> imageKeys) {
        Product product = findById(id);
        List<String> next = imageKeys == null ? new ArrayList<>() : new ArrayList<>(imageKeys);

        // Whatever the product no longer points at is removed from the bucket too. Done here, on save,
        // rather than when the user clicks the little x: until the change is committed the image is still
        // the product's, and deleting it on the click would destroy it for anyone who then cancelled the
        // form - or who was looking at the same product in another tab.
        List<String> dropped = product.getImageKeys().stream()
                .filter(key -> key != null && !next.contains(key))
                .toList();

        product.setImageKeys(next);
        Product saved = productRepository.save(product);
        dropped.forEach(this::discardImage);
        auditService.record(AuditService.ENTITY_PRODUCT, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    /**
     * Removes one object and the row that recorded it.
     *
     * <p>Failures are swallowed deliberately: an image that cannot be deleted is wasted space, while an
     * exception here would fail the product save that the user actually asked for. The row is left in
     * place when the object delete fails, so the file stays accounted for in the usage figures.</p>
     */
    private void discardImage(String key) {
        try {
            storageService.delete(key);
            storedFiles.findByStorageKey(key).ifPresent(storedFiles::delete);
        } catch (RuntimeException e) {
            log.warn("Could not delete image {} from storage", key, e);
        }
    }

    @Transactional
    public void delete(Long id) {
        Product product = findById(id);
        String name = product.getName();
        warehouseStockRepository.deleteByProductId(id);
        productBatchRepository.deleteByProductId(id);
        productRepository.delete(product);
        auditService.record(AuditService.ENTITY_PRODUCT, id, AuditAction.DELETE, name);
    }

    /** Trims a string and returns null when it is null or blank (so the unique SKU stores NULL, not ""). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}