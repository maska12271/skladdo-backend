package com.example.kladdo.config;

import com.example.kladdo.model.Category;
import com.example.kladdo.model.Client;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.CompanyType;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.PartnerCategory;
import com.example.kladdo.model.PenaltyPeriod;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.ProductBatch;
import com.example.kladdo.model.TaxRate;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.PurchaseOrderItem;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.model.SalesOrderItem;
import com.example.kladdo.model.SalesOrderItemBatch;
import com.example.kladdo.model.Tender;
import com.example.kladdo.model.TenderParticipant;
import com.example.kladdo.model.User;
import com.example.kladdo.model.UserPermission;
import com.example.kladdo.model.ConnectionStatus;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.model.WarehouseConnection;
import com.example.kladdo.model.WarehouseMethod;
import com.example.kladdo.model.WarehouseStock;
import com.example.kladdo.repository.CategoryRepository;
import com.example.kladdo.repository.ClientRepository;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.CompanySettingsRepository;
import com.example.kladdo.repository.ManufacturerRepository;
import com.example.kladdo.repository.PartnerCategoryRepository;
import com.example.kladdo.repository.ProductBatchRepository;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import com.example.kladdo.repository.TaxRateRepository;
import com.example.kladdo.repository.TenderRepository;
import com.example.kladdo.repository.UserPermissionRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.repository.WarehouseConnectionRepository;
import com.example.kladdo.repository.WarehouseRepository;
import com.example.kladdo.repository.WarehouseStockRepository;
import com.example.kladdo.security.CustomUserDetails;
import com.example.kladdo.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Seeds a realistic demo company on first startup (when no users exist yet): roughly a 20-person
 * trading business with three years of catalogue, purchase, sales and tender history. Because
 * {@code spring.jpa.hibernate.ddl-auto=create} rebuilds the schema on every start, this effectively
 * clears all previous data and replaces it with a fresh dataset. Disable via {@code app.seed.enabled=false}.
 *
 * <p>The business entities are {@code @TenantId}-scoped, so {@link TenantContext} is bound to the
 * company while they are persisted - otherwise Hibernate would stamp them with the "no tenant"
 * sentinel and they would be invisible to the application.</p>
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String COMPANY_NAME = "Nordic Trade OÜ";

    // A standalone, empty company (separate tenant from the demo) with its own owner account, for
    // entering real data. Created idempotently on startup and keyed on the owner email.
    /**
     * A third-party logistics company, so the warehouse-partner feature has something to show out of the
     * box: a warehouse account already connected to the demo company. Its owner can switch between
     * companies from the header picker.
     */
    private static final String LOGISTICS_COMPANY_NAME = "Baltic Logistics OÜ";
    private static final String LOGISTICS_OWNER_NAME = "Rasmus Kask";
    private static final String LOGISTICS_OWNER_EMAIL = "owner@balticlogistics.ee";
    private static final String LOGISTICS_OWNER_PASSWORD = "logistics123";
    /** The demo company's own warehouse that the partner is given access to. */
    private static final String PARTNER_WAREHOUSE_NAME = "Satellite Depot";

    private static final String STANDALONE_COMPANY_NAME = "Symed";
    private static final String STANDALONE_OWNER_NAME = "Svetlana Zolotarjova";
    private static final String STANDALONE_OWNER_EMAIL = "svetlana.zolotarjova@symed.ee";
    private static final String STANDALONE_OWNER_PASSWORD = "12345678";

    /** Fixed seed so the generated dataset is the same on every rebuild. */
    private static final Random RANDOM = new Random(20240617L);

    private static final int PRODUCT_COUNT = 80;
    private static final int PURCHASE_ORDER_COUNT = 140;
    private static final int SALES_ORDER_COUNT = 190;
    private static final int TENDER_COUNT = 48;

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final CategoryRepository categoryRepository;
    private final PartnerCategoryRepository partnerCategoryRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final TenderRepository tenderRepository;
    private final CompanySettingsRepository companySettingsRepository;
    private final TaxRateRepository taxRateRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseStockRepository warehouseStockRepository;
    private final ProductBatchRepository productBatchRepository;
    private final WarehouseConnectionRepository warehouseConnectionRepository;
    private final PasswordEncoder passwordEncoder;

    private int poSeq = 0;
    private int soSeq = 0;
    private int tenderSeq = 0;
    private int skuSeq = 0;
    private int lotSeq = 0;

    public DataInitializer(CompanyRepository companyRepository,
                           UserRepository userRepository,
                           UserPermissionRepository userPermissionRepository,
                           CategoryRepository categoryRepository,
                           PartnerCategoryRepository partnerCategoryRepository,
                           ManufacturerRepository manufacturerRepository,
                           ClientRepository clientRepository,
                           ProductRepository productRepository,
                           PurchaseOrderRepository purchaseOrderRepository,
                           SalesOrderRepository salesOrderRepository,
                           TenderRepository tenderRepository,
                           CompanySettingsRepository companySettingsRepository,
                           TaxRateRepository taxRateRepository,
                           WarehouseRepository warehouseRepository,
                           WarehouseStockRepository warehouseStockRepository,
                           ProductBatchRepository productBatchRepository,
                           WarehouseConnectionRepository warehouseConnectionRepository,
                           PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.categoryRepository = categoryRepository;
        this.partnerCategoryRepository = partnerCategoryRepository;
        this.manufacturerRepository = manufacturerRepository;
        this.clientRepository = clientRepository;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.tenderRepository = tenderRepository;
        this.companySettingsRepository = companySettingsRepository;
        this.taxRateRepository = taxRateRepository;
        this.warehouseRepository = warehouseRepository;
        this.warehouseStockRepository = warehouseStockRepository;
        this.productBatchRepository = productBatchRepository;
        this.warehouseConnectionRepository = warehouseConnectionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        List<Company> existing = companyRepository.findAll();
        // Our dataset is already present - leave the bulk of it alone so it survives restarts under
        // ddl-auto=update, but idempotently top up data introduced by newer features (company
        // settings, tax rates, a warehouse test account) so existing databases get them without a wipe.
        Company demo = existing.stream().filter(c -> COMPANY_NAME.equals(c.getName())).findFirst().orElse(null);
        // A demo that already has lot data survives restarts (top up newer features only). A demo that
        // predates lot/SKU tracking is treated as stale and fully reseeded so the catalogue gains
        // warehouse methods, the stock gains lots, and orders gain lot numbers / allocations.
        if (demo != null && hasBatchData(demo)) {
            backfillNewerFeatures(demo);
            ensureStandaloneCompany(STANDALONE_COMPANY_NAME, STANDALONE_OWNER_EMAIL, STANDALONE_OWNER_NAME, STANDALONE_OWNER_PASSWORD);
            ensureWarehousePartnerDemo(demo); // after the standalone company: it sends the pending request
            return;
        }
        // No demo yet, or a pre-lot demo / other leftover data: wipe once so the fresh, lot-aware
        // dataset becomes the only data.
        if (!existing.isEmpty() || userRepository.count() > 0) {
            log.info("Replacing existing data with the fresh demo dataset (now with lot & SKU tracking)...");
            wipeAll(existing);
        }

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusYears(3);

        Company company = new Company();
        company.setName(COMPANY_NAME);
        company.setRegistrationCode("EE-10234567");
        company.setActive(true);
        company = companyRepository.save(company);

        Authors authors = seedUsers(company);
        ensureWarehouseTestAccounts(company);

        // Bind the tenant for the whole batch so every @TenantId row is stamped with this company.
        TenantContext.setCompanyId(company.getId());
        try {
            List<Category> categories = categoryRepository.saveAll(buildCategories());
            List<PartnerCategory> partnerCategories = partnerCategoryRepository.saveAll(buildPartnerCategories());

            List<Manufacturer> manufacturers = buildManufacturers();
            manufacturers.forEach(m -> m.setCategories(randomPartnerCategories(partnerCategories)));
            manufacturers = manufacturerRepository.saveAll(manufacturers);

            List<Client> clients = clientRepository.saveAll(buildClients());

            // Company-wide settings and the tax-rate catalogue. Products are stamped with the default
            // (standard VAT) rate so the catalogue has tax-inclusive prices to display out of the box.
            seedCompanySettings();
            TaxRate standardRate = seedTaxRates();
            List<Product> products = buildProducts(categories, manufacturers);
            products.forEach(p -> p.setTaxRate(standardRate));
            products = productRepository.saveAll(products);

            // Warehouses must be seeded before orders so orders can reference them.
            List<Warehouse> warehouses = seedWarehouses();
            Warehouse mainWarehouse = warehouses.get(0);
            Warehouse satellite = warehouses.get(1);
            seedWarehouseStock(products, mainWarehouse, satellite);

            // Assign the two warehouse test accounts to the main warehouse.
            for (String email : List.of("warehouse@demo.com", "warehouse.noprices@demo.com")) {
                userRepository.findByEmail(email).ifPresent(u ->
                        warehouseRepository.assignUserToWarehouse(u.getId(), mainWarehouse.getId()));
            }

            // Orders are saved grouped by author (under that user's security context) so the
            // @CreatedBy column is populated - this is what powers the user-profile performance tab.
            saveAttributed(buildPurchaseOrders(manufacturers, products, start, today, mainWarehouse, satellite), authors.purchase(), purchaseOrderRepository);
            saveAttributed(buildSalesOrders(clients, products, start, today, mainWarehouse, satellite), authors.sales(), salesOrderRepository);
            tenderRepository.saveAll(buildTenders(clients, manufacturers, start, today));

            log.info("====================================================================");
            log.info("Seeded demo company '{}' with 3 years of data:", company.getName());
            log.info("  users         : {}", userRepository.count());
            log.info("  categories    : {}", categories.size());
            log.info("  manuf. cats   : {}", partnerCategories.size());
            log.info("  manufacturers : {}", manufacturers.size());
            log.info("  clients       : {}", clients.size());
            log.info("  products      : {}", products.size());
            log.info("  purchase orders: {}", PURCHASE_ORDER_COUNT);
            log.info("  sales orders  : {}", SALES_ORDER_COUNT);
            log.info("  tenders       : {}", TENDER_COUNT);
            log.info("Login: owner@demo.com / owner123  (admin@demo.com / admin123; other staff use 'password123')");
            log.info("====================================================================");
        } finally {
            TenantContext.clear();
        }

        // A clean, standalone tenant to enter real data into, separate from the demo company above.
        ensureStandaloneCompany(STANDALONE_COMPANY_NAME, STANDALONE_OWNER_EMAIL, STANDALONE_OWNER_NAME, STANDALONE_OWNER_PASSWORD);
        // A third company that runs a warehouse for the demo company, so partners are visible out of the box.
        ensureWarehousePartnerDemo(company);
    }

    /**
     * Idempotently ensures a standalone (non-demo) company exists with its own OWNER account, giving a
     * clean tenant to enter real data into alongside the demo dataset. Keyed on the owner's email
     * (globally unique), so it is a no-op once the account exists and survives restarts. Company and
     * {@link User} are not {@code @TenantId}-scoped, so no tenant binding is needed here.
     */
    private void ensureStandaloneCompany(String companyName, String ownerEmail, String ownerName, String ownerPassword) {
        if (userRepository.findByEmail(ownerEmail).isPresent()) {
            return;
        }
        Company company = new Company();
        company.setName(companyName);
        company.setActive(true);
        company = companyRepository.save(company);
        createUser(company, ownerEmail, ownerPassword, ownerName, Role.OWNER);
        log.info("Created standalone company '{}' with owner account {}.", companyName, ownerEmail);
    }

    /**
     * Idempotently sets up the warehouse-partner scenario so the feature has something to show without
     * anyone having to wire two companies together by hand: a logistics company connected to the demo
     * company, exactly as if the demo company had issued a code and the logistics company had redeemed it.
     * Its owner can then switch into the demo company from the header picker and work the warehouse they
     * were given.
     *
     * <p>Keyed on the logistics owner's email (globally unique), so creating is a no-op once done - but
     * <strong>not a blind return</strong>: a demo company seeded before the account types existed is
     * already there and still reads as a business, which quietly disables the whole feature for it. So an
     * existing one has its type corrected first.</p>
     *
     * <p>Note what it does <strong>not</strong> create. Baltic Logistics is a
     * {@link CompanyType#WAREHOUSE} account, so it has no warehouse of its own and no catalogue to put in
     * one; the site it works is {@value #PARTNER_WAREHOUSE_NAME}, which belongs to the demo company and
     * always did. Nothing is mirrored or moved - the connection only decides who may reach it.</p>
     */
    private void ensureWarehousePartnerDemo(Company demoCompany) {
        User existingOwner = userRepository.findByEmail(LOGISTICS_OWNER_EMAIL).orElse(null);
        final Company logistics;
        if (existingOwner != null) {
            logistics = existingOwner.getCompany();
            repairWarehouseAccountType(logistics);
        } else {
            Company newCompany = new Company();
            newCompany.setName(LOGISTICS_COMPANY_NAME);
            newCompany.setActive(true);
            // A pure 3PL: no catalogue, orders or warehouses of its own — it works inside its clients.
            newCompany.setType(CompanyType.WAREHOUSE);
            logistics = companyRepository.save(newCompany);
            createUser(logistics, LOGISTICS_OWNER_EMAIL, LOGISTICS_OWNER_PASSWORD, LOGISTICS_OWNER_NAME, Role.OWNER);
        }
        if (logistics == null) {
            return;
        }
        ensureDemoConnection(logistics, demoCompany);
    }

    /**
     * Connects the demo logistics company to the demo company if nothing live joins them yet — on a fresh
     * database that is the initial wiring, and on an upgraded one it restores a connection that an earlier
     * schema change could not carry across. Without it the demo silently has no partner at all.
     */
    private void ensureDemoConnection(Company logistics, Company demoCompany) {
        boolean alreadyConnected = warehouseConnectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
                        logistics.getId(), demoCompany.getId(), ConnectionStatus.ACTIVE)
                .isPresent();
        if (alreadyConnected) {
            return;
        }

        // One of the demo company's own warehouses is handed to the partner — the client picks which.
        Long assigned = TenantContext.callAs(demoCompany.getId(), () -> warehouseRepository.findAll().stream()
                .filter(w -> PARTNER_WAREHOUSE_NAME.equals(w.getName()))
                .map(Warehouse::getId)
                .findFirst()
                .orElse(null));

        WarehouseConnection active = new WarehouseConnection();
        active.setWarehouseCompanyId(logistics.getId());
        active.setClientCompanyId(demoCompany.getId());
        active.setStatus(ConnectionStatus.ACTIVE);
        active.setCanSeePrices(false);
        if (assigned != null) {
            active.setWarehouseIds(new LinkedHashSet<>(List.of(assigned)));
        }
        active.setCreatedAt(Instant.now().minus(Duration.ofDays(30)));
        warehouseConnectionRepository.save(active);

        log.info("Warehouse partner '{}' (owner {} / {}) works '{}' for {}.",
                LOGISTICS_COMPANY_NAME, LOGISTICS_OWNER_EMAIL, LOGISTICS_OWNER_PASSWORD,
                PARTNER_WAREHOUSE_NAME, demoCompany.getName());
    }

    /**
     * Makes the demo logistics company a {@link CompanyType#WAREHOUSE} account when an older database left
     * it reading as a business. The account type is immutable through the app - deliberately - so a
     * database seeded before it existed has no other way back to a correct demo.
     */
    private void repairWarehouseAccountType(Company logistics) {
        if (logistics == null || logistics.isWarehouseAccount()) {
            return;
        }
        companyRepository.setTypeDirectly(logistics.getId(), CompanyType.WAREHOUSE.name());
        log.info("Corrected '{}' to a WAREHOUSE account.", logistics.getName());
    }

    /** True when the demo company already has lot (batch) data — i.e. it post-dates lot/SKU tracking. */
    private boolean hasBatchData(Company demo) {
        TenantContext.setCompanyId(demo.getId());
        try {
            return productBatchRepository.count() > 0;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Removes all existing data so the demo dataset can replace it. Non-tenant rows go first
     * (permissions -> users), then each company's tenant-scoped rows are deleted in FK-safe order
     * (orders and tenders cascade to their items/participants), and finally the companies.
     */
    private void wipeAll(List<Company> companies) {
        warehouseRepository.clearAllWarehouseAssignments(); // join table first (FK to both user and warehouse)
        userPermissionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        for (Company company : companies) {
            TenantContext.setCompanyId(company.getId());
            try {
                purchaseOrderRepository.deleteAll();   // cascades purchase order items
                salesOrderRepository.deleteAll();      // cascades sales order items + their lot allocations
                tenderRepository.deleteAll();          // cascades tender participants
                productBatchRepository.deleteAll();    // lots: after sales allocations (FK), before products/warehouses
                warehouseStockRepository.deleteAll();  // FK to product and warehouse
                productRepository.deleteAll();
                taxRateRepository.deleteAll();
                companySettingsRepository.deleteAll();
                warehouseRepository.deleteAll();
                manufacturerRepository.deleteAll();  // clears the manufacturer_category join rows
                categoryRepository.deleteAll();
                partnerCategoryRepository.deleteAll(); // after the owning side has released its join rows
                clientRepository.deleteAll();
            } finally {
                TenantContext.clear();
            }
        }

        companyRepository.deleteAllInBatch();
    }

    // ---------------------------------------------------------------------------------------------
    // Users (20 staff) + permission profiles
    // ---------------------------------------------------------------------------------------------

    /** Order-author pools: who is credited as the creator of seeded sales / purchase orders. */
    private record Authors(List<User> sales, List<User> purchase) {
    }

    private Authors seedUsers(Company company) {
        List<UserPermission> permissions = new ArrayList<>();
        List<User> salesAuthors = new ArrayList<>();
        List<User> purchaseAuthors = new ArrayList<>();

        // Management - full access by role, no permission rows. They can author either kind of order.
        User owner = createUser(company, "owner@demo.com", "owner123", "Anna Korhonen", Role.OWNER);
        User admin = createUser(company, "admin@demo.com", "admin123", "Mikko Virtanen", Role.ADMINISTRATOR);
        User priit = createUser(company, "priit.tamm@nordictrade.ee", "password123", "Priit Tamm", Role.ADMINISTRATOR);
        salesAuthors.add(owner);
        salesAuthors.add(admin);
        salesAuthors.add(priit);
        purchaseAuthors.add(owner);
        purchaseAuthors.add(admin);
        purchaseAuthors.add(priit);

        // Sales team: own sales orders, clients and tenders; can browse products.
        for (String[] s : new String[][]{
                {"kati.saar@nordictrade.ee", "Kati Saar"},
                {"jaan.lepik@nordictrade.ee", "Jaan Lepik"},
                {"liis.magi@nordictrade.ee", "Liis Mägi"},
                {"siim.tamm@nordictrade.ee", "Siim Tamm"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            grant(permissions, u, PermissionModule.SALES_ORDERS, true, true, true, false);
            // Sales staff invoice the orders they close: generate, mark paid, view (no void).
            grant(permissions, u, PermissionModule.INVOICES, true, true, true, false);
            grant(permissions, u, PermissionModule.CLIENTS, true, true, true, false);
            grant(permissions, u, PermissionModule.TENDERS, true, true, true, false);
            grant(permissions, u, PermissionModule.PRODUCTS, true, false, false, false);
            salesAuthors.add(u);
        }

        // Purchasing team: own purchase orders, manufacturers, products and categories.
        for (String[] s : new String[][]{
                {"toomas.kask@nordictrade.ee", "Toomas Kask"},
                {"eero.salu@nordictrade.ee", "Eero Salu"},
                {"maarja.ilves@nordictrade.ee", "Maarja Ilves"},
                {"laura.niit@nordictrade.ee", "Laura Niit"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            grant(permissions, u, PermissionModule.PURCHASE_ORDERS, true, true, true, false);
            grant(permissions, u, PermissionModule.MANUFACTURERS, true, true, true, false);
            grant(permissions, u, PermissionModule.PRODUCTS, true, true, true, false);
            grant(permissions, u, PermissionModule.CATEGORIES, true, true, true, false);
            // Purchasers also keep stock accurate as deliveries arrive.
            grant(permissions, u, PermissionModule.INVENTORY, true, true, false, false);
            purchaseAuthors.add(u);
        }

        // Warehouse staff: fulfil orders (view + change status) and keep stock via inventory
        // adjustments. The first does not see prices, to demonstrate the per-account toggle.
        int warehouseIdx = 0;
        for (String[] s : new String[][]{
                {"andres.pold@nordictrade.ee", "Andres Põld"},
                {"kristjan.rand@nordictrade.ee", "Kristjan Rand"},
                {"mart.kuusk@nordictrade.ee", "Mart Kuusk"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.WAREHOUSE);
            u.setCanSeePrices(warehouseIdx++ != 0);
            userRepository.save(u);
            grant(permissions, u, PermissionModule.PRODUCTS, true, false, false, false);
            grant(permissions, u, PermissionModule.PURCHASE_ORDERS, true, false, true, false);
            grant(permissions, u, PermissionModule.SALES_ORDERS, true, false, true, false);
            grant(permissions, u, PermissionModule.INVENTORY, true, true, false, false);
        }

        // Tender specialists.
        for (String[] s : new String[][]{
                {"tiina.org@nordictrade.ee", "Tiina Org"},
                {"heli.koppel@nordictrade.ee", "Heli Koppel"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            grant(permissions, u, PermissionModule.TENDERS, true, true, true, true);
            grant(permissions, u, PermissionModule.CLIENTS, true, true, true, false);
            grant(permissions, u, PermissionModule.PRODUCTS, true, false, false, false);
            grant(permissions, u, PermissionModule.SALES_ORDERS, true, false, false, false);
        }

        // Senior operators: everything except delete. They author both kinds of order.
        for (String[] s : new String[][]{
                {"marek.vaher@nordictrade.ee", "Marek Vaher"},
                {"piret.sepp@nordictrade.ee", "Piret Sepp"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            for (PermissionModule m : PermissionModule.values()) {
                grant(permissions, u, m, true, true, true, false);
            }
            salesAuthors.add(u);
            purchaseAuthors.add(u);
        }

        // Read-only staff (e.g. accounting / analysts).
        for (String[] s : new String[][]{
                {"karl.oun@nordictrade.ee", "Karl Õun"},
                {"riina.truu@nordictrade.ee", "Riina Truu"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            for (PermissionModule m : PermissionModule.values()) {
                grant(permissions, u, m, true, false, false, false);
            }
        }

        userPermissionRepository.saveAll(permissions);
        return new Authors(salesAuthors, purchaseAuthors);
    }

    /**
     * Saves orders bucketed by a randomly-assigned author, persisting each bucket under that user's
     * security context so JPA auditing stamps {@code createdById}. Runs inside the already-bound
     * tenant context, which it leaves untouched.
     */
    private <T> void saveAttributed(List<T> orders, List<User> authors, JpaRepository<T, Long> repo) {
        Map<Long, List<T>> grouped = new HashMap<>();
        Map<Long, User> byId = new HashMap<>();
        for (T order : orders) {
            User author = authors.get(RANDOM.nextInt(authors.size()));
            grouped.computeIfAbsent(author.getId(), k -> new ArrayList<>()).add(order);
            byId.put(author.getId(), author);
        }
        for (Map.Entry<Long, List<T>> entry : grouped.entrySet()) {
            runAs(byId.get(entry.getKey()), () -> repo.saveAll(entry.getValue()));
        }
    }

    /** Executes {@code action} with the given user as the authenticated principal, then clears it. */
    private void runAs(User user, Runnable action) {
        CustomUserDetails principal = new CustomUserDetails(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private User createUser(Company company, String email, String password, String fullName, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setRole(role);
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);
        return userRepository.save(user);
    }

    private void grant(List<UserPermission> sink, User user, PermissionModule module,
                       boolean view, boolean create, boolean edit, boolean delete) {
        UserPermission p = new UserPermission();
        p.setUser(user);
        p.setModule(module);
        p.setCanView(view);
        p.setCanCreate(create);
        p.setCanEdit(edit);
        p.setCanDelete(delete);
        sink.add(p);
    }

    // ---------------------------------------------------------------------------------------------
    // Backfill for existing databases (idempotent)
    // ---------------------------------------------------------------------------------------------

    /**
     * Tops up a demo company that predates newer features so it gains them without a wipe: company
     * settings, the tax-rate catalogue (stamped onto products that have no rate yet) and two warehouse
     * test accounts. Each step is guarded so repeated startups are no-ops.
     */
    private void backfillNewerFeatures(Company company) {
        boolean changed = false;
        TenantContext.setCompanyId(company.getId());
        try {
            if (companySettingsRepository.findFirstByOrderByIdAsc().isEmpty()) {
                seedCompanySettings();
                log.info("Backfill: created company settings for '{}'.", company.getName());
                changed = true;
            }
            if (taxRateRepository.count() == 0) {
                TaxRate standardRate = seedTaxRates();
                List<Product> products = productRepository.findAll();
                for (Product p : products) {
                    if (p.getTaxRate() == null) {
                        p.setTaxRate(standardRate);
                    }
                }
                productRepository.saveAll(products);
                log.info("Backfill: created tax rates and applied the default to {} products.", products.size());
                changed = true;
            }
            if (ensureWarehouseTestAccounts(company)) {
                changed = true;
            }
            if (warehouseRepository.count() == 0) {
                List<Warehouse> warehouses = seedWarehouses();
                Warehouse main = warehouses.get(0);
                Warehouse satellite = warehouses.get(1);
                // Migrate each product's existing total stock into the main warehouse.
                List<Product> products = productRepository.findAll();
                seedWarehouseStock(products, main, satellite);
                // Assign all existing orders to the main warehouse.
                List<PurchaseOrder> pos = purchaseOrderRepository.findAll();
                pos.forEach(o -> o.setWarehouse(main));
                purchaseOrderRepository.saveAll(pos);
                List<SalesOrder> sos = salesOrderRepository.findAll();
                sos.forEach(o -> o.setWarehouse(main));
                salesOrderRepository.saveAll(sos);
                // Assign warehouse test accounts to the main warehouse.
                for (String email : List.of("warehouse@demo.com", "warehouse.noprices@demo.com")) {
                    userRepository.findByEmail(email).ifPresent(u ->
                            warehouseRepository.assignUserToWarehouse(u.getId(), main.getId()));
                }
                log.info("Backfill: created 2 warehouses, migrated stock for {} products, assigned {} POs and {} SOs.",
                        products.size(), pos.size(), sos.size());
                changed = true;
            }
            // Manufacturer categories (what a manufacturer produces) are a newer feature: seed the
            // taxonomy and tag existing manufacturers once, so demos that predate it have data to filter.
            if (partnerCategoryRepository.count() == 0) {
                List<PartnerCategory> partnerCategories = partnerCategoryRepository.saveAll(buildPartnerCategories());
                List<Manufacturer> manufacturers = manufacturerRepository.findAll();
                manufacturers.forEach(m -> m.setCategories(randomPartnerCategories(partnerCategories)));
                manufacturerRepository.saveAll(manufacturers);
                log.info("Backfill: created {} manufacturer categories and tagged {} manufacturers.",
                        partnerCategories.size(), manufacturers.size());
                changed = true;
            }
            // Strip catalogue-page access (categories/manufacturers) from the warehouse test accounts
            // if an earlier build granted it - warehouse staff don't manage the catalogue.
            cleanupWarehouseTestPermissions();
        } finally {
            TenantContext.clear();
        }
        if (changed) {
            log.info("====================================================================");
            log.info("Backfill complete for '{}'. Warehouse test logins:", company.getName());
            log.info("  warehouse@demo.com          / warehouse123  (WAREHOUSE, sees prices)");
            log.info("  warehouse.noprices@demo.com / warehouse123  (WAREHOUSE, no prices)");
            log.info("====================================================================");
        }
    }

    /** Removes catalogue-page (categories/manufacturers) access from the warehouse test accounts. */
    private void cleanupWarehouseTestPermissions() {
        for (String email : List.of("warehouse@demo.com", "warehouse.noprices@demo.com")) {
            userRepository.findByEmail(email).ifPresent(u -> {
                List<UserPermission> stale = userPermissionRepository.findByUserId(u.getId()).stream()
                        .filter(p -> p.getModule() == PermissionModule.CATEGORIES
                                || p.getModule() == PermissionModule.MANUFACTURERS)
                        .toList();
                if (!stale.isEmpty()) {
                    userPermissionRepository.deleteAll(stale);
                    log.info("Backfill: removed catalogue-page access from {}.", email);
                }
            });
        }
    }

    /** Creates the two warehouse test accounts if absent. Returns true if any were created. */
    private boolean ensureWarehouseTestAccounts(Company company) {
        boolean a = createWarehouseAccount(company, "warehouse@demo.com", "Wendy Warehouse", true);
        boolean b = createWarehouseAccount(company, "warehouse.noprices@demo.com", "Nigel No-Prices", false);
        return a || b;
    }

    private boolean createWarehouseAccount(Company company, String email, String fullName, boolean canSeePrices) {
        if (userRepository.existsByEmail(email)) {
            return false;
        }
        User u = createUser(company, email, "warehouse123", fullName, Role.WAREHOUSE);
        u.setCanSeePrices(canSeePrices);
        userRepository.save(u);

        List<UserPermission> perms = new ArrayList<>();
        grant(perms, u, PermissionModule.PRODUCTS, true, false, false, false);
        grant(perms, u, PermissionModule.PURCHASE_ORDERS, true, false, true, false);
        grant(perms, u, PermissionModule.SALES_ORDERS, true, false, true, false);
        grant(perms, u, PermissionModule.INVENTORY, true, true, false, false);
        grant(perms, u, PermissionModule.WAREHOUSES, true, false, false, false);
        userPermissionRepository.saveAll(perms);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Company settings & tax rates
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Warehouses
    // ---------------------------------------------------------------------------------------------

    private List<Warehouse> seedWarehouses() {
        Warehouse main = new Warehouse();
        main.setName("Main Warehouse");
        main.setAddress("Tööstuse tee 5, Tallinn");
        main.setActive(true);
        main = warehouseRepository.save(main);

        Warehouse satellite = new Warehouse();
        satellite.setName("Satellite Depot");
        satellite.setAddress("Tehase 12, Tartu");
        satellite.setActive(true);
        satellite = warehouseRepository.save(satellite);

        return List.of(main, satellite);
    }

    /**
     * Distributes each product's existing stock across warehouses (roughly 70/30 main/satellite) and,
     * beneath each per-warehouse total, splits it into 1–3 lots so the on-hand quantity is expressed
     * as {@link ProductBatch} rows that sum back to the warehouse total.
     */
    private void seedWarehouseStock(List<Product> products, Warehouse main, Warehouse satellite) {
        List<WarehouseStock> stocks = new ArrayList<>();
        List<ProductBatch> batches = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Product p : products) {
            int total = p.getStockQuantity() != null ? p.getStockQuantity() : 0;
            if (total == 0) continue;
            // ~30% of products get a satellite split for realistic variety.
            int satQty = RANDOM.nextInt(100) < 30 ? Math.max(1, (int) (total * 0.3)) : 0;
            int mainQty = total - satQty;
            if (mainQty > 0) {
                stocks.add(warehouseStock(main, p, mainQty));
                addLots(batches, p, main, mainQty, today);
            }
            if (satQty > 0) {
                stocks.add(warehouseStock(satellite, p, satQty));
                addLots(batches, p, satellite, satQty, today);
            }
        }
        warehouseStockRepository.saveAll(stocks);
        productBatchRepository.saveAll(batches);
    }

    private WarehouseStock warehouseStock(Warehouse warehouse, Product product, int qty) {
        WarehouseStock s = new WarehouseStock();
        s.setWarehouse(warehouse);
        s.setProduct(product);
        s.setQuantity(qty);
        return s;
    }

    /** Splits a warehouse's on-hand quantity for a product into 1–3 lots that sum back to it. */
    private void addLots(List<ProductBatch> sink, Product product, Warehouse warehouse, int qty, LocalDate today) {
        int lotCount = qty < 10 ? 1 : 1 + RANDOM.nextInt(3); // 1..3
        int remaining = qty;
        for (int i = 0; i < lotCount && remaining > 0; i++) {
            int part = (i == lotCount - 1) ? remaining : Math.max(1, remaining / (lotCount - i));
            remaining -= part;
            ProductBatch b = new ProductBatch();
            b.setProduct(product);
            b.setWarehouse(warehouse);
            b.setLotNumber(String.format("LOT-%d-%04d", today.getYear(), ++lotSeq));
            b.setQuantity(part);
            b.setOriginalQuantity(part);
            LocalDate production = today.minusDays(30 + RANDOM.nextInt(700));
            b.setProductionDate(production);
            // ~60% of lots carry an expiry; the rest never expire (consumed last under FEFO).
            if (RANDOM.nextInt(100) < 60) {
                b.setExpiryDate(production.plusMonths(12 + RANDOM.nextInt(30)));
            }
            sink.add(b);
        }
    }

    /** Seeds the company's settings row with realistic invoicing/penalty/prepayment defaults. */
    private void seedCompanySettings() {
        CompanySettings settings = new CompanySettings();
        settings.setCurrency("EUR");
        settings.setPricesIncludeTax(false);
        settings.setInvoiceNumberPrefix("INV-");
        settings.setInvoicePaymentTermDays(14);
        settings.setLatePaymentPenaltyPercent(new BigDecimal("0.5"));
        settings.setPenaltyPeriod(PenaltyPeriod.DAILY);
        settings.setDefaultPrepaymentPercent(new BigDecimal("30"));
        // Seller details so a generated demo invoice looks complete.
        settings.setCompanyAddress("Pärnu mnt 12, 10148 Tallinn, Estonia");
        settings.setCompanyEmail("billing@nordictrade.ee");
        settings.setCompanyPhone("+372 600 1234");
        settings.setVatNumber("EE101234567");
        settings.setBankName("Nordic Bank AS");
        settings.setBankIban("EE38 2200 2210 2014 5685");
        settings.setDefaultProductUnit("pcs");
        settings.setDefaultMinimumStock(5);
        companySettingsRepository.save(settings);
    }

    /** Seeds the Estonian VAT bands and returns the default (standard) rate. */
    private TaxRate seedTaxRates() {
        TaxRate standard = taxRate("Standard VAT", new BigDecimal("22"), true);
        taxRate("Reduced VAT", new BigDecimal("9"), false);
        taxRate("Zero-rated", new BigDecimal("0"), false);
        return standard;
    }

    private TaxRate taxRate(String name, BigDecimal percentage, boolean isDefault) {
        TaxRate rate = new TaxRate();
        rate.setName(name);
        rate.setPercentage(percentage);
        rate.setDefault(isDefault);
        rate.setActive(true);
        return taxRateRepository.save(rate);
    }

    // ---------------------------------------------------------------------------------------------
    // Reference data
    // ---------------------------------------------------------------------------------------------

    private static final String[][] CATEGORIES = {
            {"Power Tools", "Corded and cordless power tools"},
            {"Hand Tools", "Manual tools for trade work"},
            {"Fasteners", "Screws, bolts, anchors and fixings"},
            {"Safety Equipment", "Personal protective equipment"},
            {"Electrical", "Cabling, switchgear and lighting"},
            {"Plumbing", "Pipes, valves and fittings"},
            {"Adhesives & Sealants", "Glues, foams and sealants"},
            {"Measuring Instruments", "Levels, gauges and meters"},
    };

    private List<Category> buildCategories() {
        List<Category> list = new ArrayList<>();
        for (String[] c : CATEGORIES) {
            Category category = new Category();
            category.setName(c[0]);
            category.setDescription(c[1]);
            category.setActive(true);
            list.add(category);
        }
        return list;
    }

    /** Manufacturer categories — the kind of goods a manufacturer (supplier) produces. */
    private static final String[][] PARTNER_CATEGORIES = {
            {"Power Tools", "Produces corded and cordless power tools"},
            {"Hand Tools", "Produces manual hand tools"},
            {"Fasteners", "Produces screws, bolts and fixings"},
            {"Safety Equipment", "Produces personal protective equipment"},
            {"Electrical", "Produces cabling, switchgear and lighting"},
            {"Measuring Instruments", "Produces levels, gauges and meters"},
    };

    private List<PartnerCategory> buildPartnerCategories() {
        List<PartnerCategory> list = new ArrayList<>();
        for (String[] c : PARTNER_CATEGORIES) {
            PartnerCategory category = new PartnerCategory();
            category.setName(c[0]);
            category.setDescription(c[1]);
            category.setActive(true);
            list.add(category);
        }
        return list;
    }

    /** Picks 1–2 distinct manufacturer categories at random, for tagging a manufacturer. */
    private Set<PartnerCategory> randomPartnerCategories(List<PartnerCategory> all) {
        if (all.isEmpty()) {
            return new HashSet<>();
        }
        Set<PartnerCategory> picked = new HashSet<>();
        int count = 1 + RANDOM.nextInt(2);
        for (int i = 0; i < count; i++) {
            picked.add(all.get(RANDOM.nextInt(all.size())));
        }
        return picked;
    }

    private static final String[][] MANUFACTURERS = {
            {"Bosch", "Germany"},
            {"Makita", "Japan"},
            {"DeWalt", "United States"},
            {"Hilti", "Liechtenstein"},
            {"Stanley", "United States"},
            {"Festool", "Germany"},
            {"Knipex", "Germany"},
            {"Fiskars", "Finland"},
            {"Würth", "Germany"},
            {"Milwaukee", "United States"},
            {"Metabo", "Germany"},
            {"3M", "United States"},
    };

    private List<Manufacturer> buildManufacturers() {
        List<Manufacturer> list = new ArrayList<>();
        for (String[] m : MANUFACTURERS) {
            Manufacturer manufacturer = new Manufacturer();
            manufacturer.setName(m[0]);
            manufacturer.setCountry(m[1]);
            String slug = m[0].toLowerCase().replace("ü", "u").replaceAll("[^a-z0-9]", "");
            manufacturer.setEmail("sales@" + slug + ".example");
            manufacturer.setPhone("+49 " + (1000000 + RANDOM.nextInt(8999999)));
            manufacturer.setWebsite("https://www." + slug + ".example");
            manufacturer.setActive(true);
            list.add(manufacturer);
        }
        return list;
    }

    private static final String[] CLIENT_NAMES = {
            "Ehitus Grupp AS", "Tallinn Construction OÜ", "Baltic Builders AS", "Nordic Infra OÜ",
            "Harju Ehitus AS", "Tartu Maja OÜ", "Pärnu Property Group AS", "Viru Renovation OÜ",
            "Saare Build AS", "Lääne Contractors OÜ", "Capital Facilities AS", "Metro Fit-Out OÜ",
            "Delta Engineering AS", "Granite Developments OÜ", "Pidev Ehitus AS",
    };

    private List<Client> buildClients() {
        List<Client> list = new ArrayList<>();
        int idx = 0;
        for (String name : CLIENT_NAMES) {
            idx++;
            Client client = new Client();
            client.setName(name);
            client.setRegistrationCode("EE-" + (10300000 + idx));
            String slug = "client" + idx;
            client.setEmail("info@" + slug + ".example");
            client.setPhone("+372 " + (5000000 + RANDOM.nextInt(2999999)));
            client.setAddress(RANDOM.nextInt(120) + " Tööstuse tee, Tallinn");
            client.setContactPerson(CONTACT_PEOPLE[idx % CONTACT_PEOPLE.length]);
            client.setActive(true);
            list.add(client);
        }
        return list;
    }

    private static final String[] CONTACT_PEOPLE = {
            "Margus Saks", "Helena Laur", "Rein Kallas", "Tarmo Välli", "Kadri Soon",
            "Indrek Raud", "Eva Mets", "Urmas Tamm", "Signe Pärn", "Jüri Aas",
    };

    // category index -> sample product names within that category
    private static final String[][] PRODUCT_NAMES = {
            {"Cordless Drill", "Angle Grinder", "Impact Driver", "Circular Saw", "Rotary Hammer", "Jigsaw", "Heat Gun"},
            {"Screwdriver Set", "Claw Hammer", "Combination Pliers", "Wrench Set", "Hex Key Set", "Wood Chisel", "Hand Saw"},
            {"Wood Screws 4x40", "Drywall Screws 3.5x35", "Hex Bolts M8", "Anchor Bolts M10", "Self-tapping Screws", "Washers M10", "Hex Nuts M8"},
            {"Safety Helmet", "Work Gloves", "Safety Goggles", "Ear Defenders", "Hi-Vis Vest", "Dust Mask FFP2", "Safety Boots"},
            {"Cable 3x2.5mm", "Junction Box IP65", "Circuit Breaker 16A", "Socket Outlet", "LED Floodlight 50W", "Extension Reel 25m", "Wire Stripper"},
            {"Copper Pipe 15mm", "Ball Valve 1/2\"", "PTFE Tape", "Pipe Wrench 14\"", "Compression Fitting", "Flexible Hose 300mm", "Drain Auger"},
            {"Silicone Sealant", "Construction Adhesive", "Epoxy Resin Kit", "Expanding Foam", "Wood Glue 1L", "Cyanoacrylate Glue", "Threadlocker 50ml"},
            {"Laser Level", "Tape Measure 5m", "Digital Caliper", "Spirit Level 60cm", "Multimeter", "Stud Finder", "Moisture Meter"},
    };

    private static final String[] UNITS = {"pcs", "pcs", "pcs", "box", "set"};

    private List<Product> buildProducts(List<Category> categories, List<Manufacturer> manufacturers) {
        List<Product> list = new ArrayList<>();
        for (int i = 0; i < PRODUCT_COUNT; i++) {
            int catIdx = i % categories.size();
            Category category = categories.get(catIdx);
            Manufacturer manufacturer = manufacturers.get(i % manufacturers.size());
            String[] names = PRODUCT_NAMES[catIdx % PRODUCT_NAMES.length];
            String baseName = names[(i / categories.size()) % names.length];

            Product product = new Product();
            product.setName(manufacturer.getName() + " " + baseName);
            product.setSku(String.format("SKU-%05d", ++skuSeq));
            product.setManufacturer(manufacturer);
            product.setCategory(category);
            product.setUnit(UNITS[RANDOM.nextInt(UNITS.length)]);
            product.setSize("");
            product.setDescription(baseName + " by " + manufacturer.getName());
            product.setPrice(money(5 + RANDOM.nextDouble() * 795));

            int minimum = 5 + RANDOM.nextInt(45);
            // ~12% of items deliberately below their minimum so the dashboard low-stock list is populated.
            int stock = RANDOM.nextInt(100) < 12 ? RANDOM.nextInt(Math.max(1, minimum)) : minimum + RANDOM.nextInt(400);
            product.setMinimumStock(minimum);
            product.setStockQuantity(stock);
            // Vary the stock-out method across the catalogue; most products use FEFO.
            product.setWarehouseMethod(pick(WarehouseMethod.FEFO, WarehouseMethod.FEFO,
                    WarehouseMethod.FEFO, WarehouseMethod.FIFO, WarehouseMethod.LIFO));
            product.setActive(RANDOM.nextInt(100) < 92);
            list.add(product);
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------
    // Purchase orders
    // ---------------------------------------------------------------------------------------------

    private List<PurchaseOrder> buildPurchaseOrders(List<Manufacturer> manufacturers, List<Product> products,
                                                    LocalDate start, LocalDate today,
                                                    Warehouse mainWarehouse, Warehouse satellite) {
        Map<Long, List<Product>> byManufacturer = new java.util.HashMap<>();
        for (Product p : products) {
            byManufacturer.computeIfAbsent(p.getManufacturer().getId(), k -> new ArrayList<>()).add(p);
        }
        List<Manufacturer> withProducts = manufacturers.stream()
                .filter(m -> byManufacturer.containsKey(m.getId()))
                .toList();

        List<PurchaseOrder> orders = new ArrayList<>();
        for (int i = 0; i < PURCHASE_ORDER_COUNT; i++) {
            Manufacturer manufacturer = withProducts.get(RANDOM.nextInt(withProducts.size()));
            List<Product> pool = byManufacturer.get(manufacturer.getId());
            LocalDate orderDate = randomDate(start, today);
            Warehouse warehouse = RANDOM.nextInt(100) < 30 ? satellite : mainWarehouse;

            PurchaseOrder po = new PurchaseOrder();
            po.setOrderNumber("PO-" + orderDate.getYear() + "-" + String.format("%04d", ++poSeq));
            po.setManufacturer(manufacturer);
            po.setOrderDate(orderDate);
            po.setExpectedDeliveryDate(orderDate.plusDays(7 + RANDOM.nextInt(24)));
            po.setDeliveryAddress(warehouse.getAddress());
            po.setWarehouse(warehouse);
            po.setStatus(orderStatusFor(orderDate, today));
            po.setNotes(RANDOM.nextInt(100) < 25 ? "Restock order" : null);

            BigDecimal subtotal = BigDecimal.ZERO;
            int lines = 1 + RANDOM.nextInt(5);
            for (int l = 0; l < lines; l++) {
                Product product = pool.get(RANDOM.nextInt(pool.size()));
                int qty = 1 + RANDOM.nextInt(50);
                // Purchase cost sits below the catalogue (sell) price.
                BigDecimal unitPrice = money(product.getPrice().doubleValue() * (0.55 + RANDOM.nextDouble() * 0.2));
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

                PurchaseOrderItem item = new PurchaseOrderItem();
                item.setPurchaseOrder(po);
                item.setProduct(product);
                item.setQuantity(qty);
                item.setUnitPrice(unitPrice);
                item.setLineTotal(lineTotal);
                // Each received line carries its lot: produced shortly before the order, with an
                // expiry on ~60% of lines (the rest are non-perishable, e.g. tools).
                item.setLotNumber(String.format("LOT-%d-%04d", orderDate.getYear(), ++lotSeq));
                LocalDate production = orderDate.minusDays(RANDOM.nextInt(120));
                item.setProductionDate(production);
                if (RANDOM.nextInt(100) < 60) {
                    item.setExpiryDate(production.plusMonths(12 + RANDOM.nextInt(24)));
                }
                po.getItems().add(item);
                subtotal = subtotal.add(lineTotal);
            }

            BigDecimal delivery = money(RANDOM.nextInt(100));
            po.setDeliveryPrice(delivery);
            po.setSubtotalAmount(subtotal.setScale(2, RoundingMode.HALF_UP));
            po.setTotalAmount(subtotal.add(delivery).setScale(2, RoundingMode.HALF_UP));
            if (po.getStatus() == OrderStatus.CLOSED || po.getStatus() == OrderStatus.SHIPPED) {
                po.setClosingDate(orderDate.plusDays(10 + RANDOM.nextInt(30)));
            }
            orders.add(po);
        }
        return orders;
    }

    // ---------------------------------------------------------------------------------------------
    // Sales orders
    // ---------------------------------------------------------------------------------------------

    private List<SalesOrder> buildSalesOrders(List<Client> clients, List<Product> products,
                                              LocalDate start, LocalDate today,
                                              Warehouse mainWarehouse, Warehouse satellite) {
        List<SalesOrder> orders = new ArrayList<>();
        for (int i = 0; i < SALES_ORDER_COUNT; i++) {
            Client client = clients.get(RANDOM.nextInt(clients.size()));
            LocalDate orderDate = randomDate(start, today);
            Warehouse warehouse = RANDOM.nextInt(100) < 30 ? satellite : mainWarehouse;

            SalesOrder so = new SalesOrder();
            so.setOrderNumber("SO-" + orderDate.getYear() + "-" + String.format("%04d", ++soSeq));
            so.setClient(client);
            so.setOrderDate(orderDate);
            so.setDeliveryAddress(client.getAddress());
            so.setWarehouse(warehouse);
            so.setStatus(orderStatusFor(orderDate, today));
            so.setNotes(RANDOM.nextInt(100) < 20 ? "Priority customer" : null);

            BigDecimal subtotal = BigDecimal.ZERO;
            int lines = 1 + RANDOM.nextInt(6);
            for (int l = 0; l < lines; l++) {
                Product product = products.get(RANDOM.nextInt(products.size()));
                int qty = 1 + RANDOM.nextInt(30);
                // Sell at catalogue price give or take 10%.
                BigDecimal unitPrice = money(product.getPrice().doubleValue() * (0.95 + RANDOM.nextDouble() * 0.15));
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

                SalesOrderItem item = new SalesOrderItem();
                item.setSalesOrder(so);
                item.setProduct(product);
                item.setQuantity(qty);
                item.setUnitPrice(unitPrice);
                item.setLineTotal(lineTotal);
                // A shipped/closed line has drawn its quantity from one or two lots (larger lines split).
                if (so.getStatus() == OrderStatus.SHIPPED || so.getStatus() == OrderStatus.CLOSED) {
                    addSaleAllocations(item, qty, orderDate);
                }
                so.getItems().add(item);
                subtotal = subtotal.add(lineTotal);
            }

            BigDecimal delivery = money(RANDOM.nextInt(150));
            so.setDeliveryPrice(delivery);
            so.setSubtotalAmount(subtotal.setScale(2, RoundingMode.HALF_UP));
            so.setTotalAmount(subtotal.add(delivery).setScale(2, RoundingMode.HALF_UP));
            if (so.getStatus() == OrderStatus.CLOSED || so.getStatus() == OrderStatus.SHIPPED) {
                so.setClosingDate(orderDate.plusDays(5 + RANDOM.nextInt(25)));
            }
            orders.add(so);
        }
        return orders;
    }

    /** Records which lots a shipped sales line drew from: one lot, or two when the line is large. */
    private void addSaleAllocations(SalesOrderItem item, int qty, LocalDate orderDate) {
        int firstQty = (qty >= 4 && RANDOM.nextInt(100) < 50) ? Math.max(1, qty / 2) : qty;
        item.getBatchAllocations().add(saleAllocation(item, firstQty, orderDate));
        int secondQty = qty - firstQty;
        if (secondQty > 0) {
            item.getBatchAllocations().add(saleAllocation(item, secondQty, orderDate));
        }
    }

    /**
     * A single lot consumed by a shipped sales line. Historical, so the source {@link ProductBatch}
     * is left null (it may be long depleted); the lot's identifying details are snapshotted on the row.
     */
    private SalesOrderItemBatch saleAllocation(SalesOrderItem item, int qty, LocalDate orderDate) {
        SalesOrderItemBatch alloc = new SalesOrderItemBatch();
        alloc.setSalesOrderItem(item);
        alloc.setProductBatch(null);
        alloc.setLotNumber(String.format("LOT-%d-%04d", orderDate.minusMonths(RANDOM.nextInt(12)).getYear(), ++lotSeq));
        LocalDate production = orderDate.minusDays(60 + RANDOM.nextInt(400));
        alloc.setProductionDate(production);
        if (RANDOM.nextInt(100) < 60) {
            alloc.setExpiryDate(production.plusMonths(12 + RANDOM.nextInt(24)));
        }
        alloc.setQuantityUsed(qty);
        return alloc;
    }

    // ---------------------------------------------------------------------------------------------
    // Tenders
    // ---------------------------------------------------------------------------------------------

    private static final String[] TENDER_SCOPES = {
            "Power tools supply", "Safety equipment framework", "Electrical materials supply",
            "Site fastener supply", "Plumbing materials tender", "Measuring instruments procurement",
            "General building supplies", "Hand tools framework agreement",
    };

    private List<Tender> buildTenders(List<Client> clients, List<Manufacturer> manufacturers,
                                      LocalDate start, LocalDate today) {
        List<Tender> tenders = new ArrayList<>();
        for (int i = 0; i < TENDER_COUNT; i++) {
            Client client = clients.get(RANDOM.nextInt(clients.size()));
            String scope = TENDER_SCOPES[RANDOM.nextInt(TENDER_SCOPES.length)];
            LocalDate publishedAt = randomDate(start, today.minusDays(14));
            LocalDate deadline = publishedAt.plusDays(14 + RANDOM.nextInt(31));

            Tender tender = new Tender();
            tender.setTitle(scope + " for " + client.getName());
            tender.setTenderNumber("TND-" + publishedAt.getYear() + "-" + String.format("%03d", ++tenderSeq));
            tender.setClient(client);
            tender.setPublishedAt(publishedAt);
            tender.setDeadline(deadline);
            tender.setStatus(tenderStatusFor(deadline, today));
            double estimated = 5000 + RANDOM.nextInt(195000);
            tender.setEstimatedValue(Math.round(estimated * 100.0) / 100.0);
            tender.setDescription(scope + " - framework agreement covering 12 months of deliveries.");

            int count = 2 + RANDOM.nextInt(4);
            TenderParticipant best = null;
            for (int p = 0; p < count; p++) {
                Manufacturer m = manufacturers.get(RANDOM.nextInt(manufacturers.size()));
                double offered = estimated * (0.8 + RANDOM.nextDouble() * 0.35);

                TenderParticipant participant = new TenderParticipant();
                participant.setTender(tender);
                participant.setManufacturerName(m.getName());
                participant.setOfferedPrice(Math.round(offered * 100.0) / 100.0);
                participant.setNotes(null);
                participant.setWinner(false);
                tender.getParticipants().add(participant);
                if (best == null || participant.getOfferedPrice() < best.getOfferedPrice()) {
                    best = participant;
                }
            }
            // The lowest offer wins once a tender is closed.
            if ("CLOSED".equals(tender.getStatus()) && best != null) {
                best.setWinner(true);
            }
            tenders.add(tender);
        }
        return tenders;
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private OrderStatus orderStatusFor(LocalDate orderDate, LocalDate today) {
        long months = ChronoUnit.MONTHS.between(orderDate, today);
        if (months >= 6) {
            return pick(OrderStatus.CLOSED, OrderStatus.CLOSED, OrderStatus.CLOSED, OrderStatus.SHIPPED, OrderStatus.CANCELLED);
        }
        if (months >= 2) {
            return pick(OrderStatus.SHIPPED, OrderStatus.CONFIRMED, OrderStatus.CLOSED, OrderStatus.IN_PROGRESS);
        }
        return pick(OrderStatus.NEW, OrderStatus.IN_PROGRESS, OrderStatus.CONFIRMED);
    }

    private String tenderStatusFor(LocalDate deadline, LocalDate today) {
        if (deadline.isBefore(today)) {
            return RANDOM.nextInt(10) < 8 ? "CLOSED" : "CANCELLED";
        }
        long days = ChronoUnit.DAYS.between(today, deadline);
        if (days < 21) {
            return RANDOM.nextInt(2) == 0 ? "IN_PROGRESS" : "PUBLISHED";
        }
        return RANDOM.nextInt(2) == 0 ? "OPEN" : "PUBLISHED";
    }

    @SafeVarargs
    private <T> T pick(T... options) {
        return options[RANDOM.nextInt(options.length)];
    }

    private LocalDate randomDate(LocalDate start, LocalDate end) {
        long startDay = start.toEpochDay();
        long endDay = end.toEpochDay();
        long day = startDay + (long) (RANDOM.nextDouble() * (endDay - startDay));
        return LocalDate.ofEpochDay(day);
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
