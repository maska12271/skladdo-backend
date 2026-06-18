package com.example.tenderapp.config;

import com.example.tenderapp.model.Category;
import com.example.tenderapp.model.Client;
import com.example.tenderapp.model.Company;
import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.PermissionModule;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.model.PurchaseOrder;
import com.example.tenderapp.model.PurchaseOrderItem;
import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.model.SalesOrderItem;
import com.example.tenderapp.model.Tender;
import com.example.tenderapp.model.TenderParticipant;
import com.example.tenderapp.model.User;
import com.example.tenderapp.model.UserPermission;
import com.example.tenderapp.repository.CategoryRepository;
import com.example.tenderapp.repository.ClientRepository;
import com.example.tenderapp.repository.CompanyRepository;
import com.example.tenderapp.repository.ManufacturerRepository;
import com.example.tenderapp.repository.ProductRepository;
import com.example.tenderapp.repository.PurchaseOrderRepository;
import com.example.tenderapp.repository.SalesOrderRepository;
import com.example.tenderapp.repository.TenderRepository;
import com.example.tenderapp.repository.UserPermissionRepository;
import com.example.tenderapp.repository.UserRepository;
import com.example.tenderapp.security.CustomUserDetails;
import com.example.tenderapp.security.TenantContext;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
    private final ManufacturerRepository manufacturerRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final TenderRepository tenderRepository;
    private final PasswordEncoder passwordEncoder;

    private int poSeq = 0;
    private int soSeq = 0;
    private int tenderSeq = 0;
    private int skuSeq = 0;

    public DataInitializer(CompanyRepository companyRepository,
                           UserRepository userRepository,
                           UserPermissionRepository userPermissionRepository,
                           CategoryRepository categoryRepository,
                           ManufacturerRepository manufacturerRepository,
                           ClientRepository clientRepository,
                           ProductRepository productRepository,
                           PurchaseOrderRepository purchaseOrderRepository,
                           SalesOrderRepository salesOrderRepository,
                           TenderRepository tenderRepository,
                           PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.categoryRepository = categoryRepository;
        this.manufacturerRepository = manufacturerRepository;
        this.clientRepository = clientRepository;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.tenderRepository = tenderRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        List<Company> existing = companyRepository.findAll();
        // Our dataset is already present - leave it alone so it survives restarts under ddl-auto=update.
        if (existing.stream().anyMatch(c -> COMPANY_NAME.equals(c.getName()))) {
            return;
        }
        // Any other (older / leftover) data is wiped once so this becomes the only dataset.
        if (!existing.isEmpty() || userRepository.count() > 0) {
            log.info("Replacing existing data with the fresh demo dataset...");
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

        // Bind the tenant for the whole batch so every @TenantId row is stamped with this company.
        TenantContext.setCompanyId(company.getId());
        try {
            List<Category> categories = categoryRepository.saveAll(buildCategories());
            List<Manufacturer> manufacturers = manufacturerRepository.saveAll(buildManufacturers());
            List<Client> clients = clientRepository.saveAll(buildClients());
            List<Product> products = productRepository.saveAll(buildProducts(categories, manufacturers));

            // Orders are saved grouped by author (under that user's security context) so the
            // @CreatedBy column is populated - this is what powers the user-profile performance tab.
            saveAttributed(buildPurchaseOrders(manufacturers, products, start, today), authors.purchase(), purchaseOrderRepository);
            saveAttributed(buildSalesOrders(clients, products, start, today), authors.sales(), salesOrderRepository);
            tenderRepository.saveAll(buildTenders(clients, manufacturers, start, today));

            log.info("====================================================================");
            log.info("Seeded demo company '{}' with 3 years of data:", company.getName());
            log.info("  users         : {}", userRepository.count());
            log.info("  categories    : {}", categories.size());
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
    }

    /**
     * Removes all existing data so the demo dataset can replace it. Non-tenant rows go first
     * (permissions -> users), then each company's tenant-scoped rows are deleted in FK-safe order
     * (orders and tenders cascade to their items/participants), and finally the companies.
     */
    private void wipeAll(List<Company> companies) {
        userPermissionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        for (Company company : companies) {
            TenantContext.setCompanyId(company.getId());
            try {
                purchaseOrderRepository.deleteAll();   // cascades purchase order items
                salesOrderRepository.deleteAll();      // cascades sales order items
                tenderRepository.deleteAll();          // cascades tender participants
                productRepository.deleteAll();
                manufacturerRepository.deleteAll();
                categoryRepository.deleteAll();
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
            purchaseAuthors.add(u);
        }

        // Warehouse: manage products, read-only on the order flow.
        for (String[] s : new String[][]{
                {"andres.pold@nordictrade.ee", "Andres Põld"},
                {"kristjan.rand@nordictrade.ee", "Kristjan Rand"},
                {"mart.kuusk@nordictrade.ee", "Mart Kuusk"},
        }) {
            User u = createUser(company, s[0], "password123", s[1], Role.USER);
            grant(permissions, u, PermissionModule.PRODUCTS, true, true, true, true);
            grant(permissions, u, PermissionModule.CATEGORIES, true, false, false, false);
            grant(permissions, u, PermissionModule.MANUFACTURERS, true, false, false, false);
            grant(permissions, u, PermissionModule.PURCHASE_ORDERS, true, false, false, false);
            grant(permissions, u, PermissionModule.SALES_ORDERS, true, false, false, false);
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
            product.setActive(RANDOM.nextInt(100) < 92);
            list.add(product);
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------
    // Purchase orders
    // ---------------------------------------------------------------------------------------------

    private List<PurchaseOrder> buildPurchaseOrders(List<Manufacturer> manufacturers, List<Product> products,
                                                    LocalDate start, LocalDate today) {
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

            PurchaseOrder po = new PurchaseOrder();
            po.setOrderNumber("PO-" + orderDate.getYear() + "-" + String.format("%04d", ++poSeq));
            po.setManufacturer(manufacturer);
            po.setOrderDate(orderDate);
            po.setExpectedDeliveryDate(orderDate.plusDays(7 + RANDOM.nextInt(24)));
            po.setDeliveryAddress("Tööstuse tee 5, Tallinn");
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
                                              LocalDate start, LocalDate today) {
        List<SalesOrder> orders = new ArrayList<>();
        for (int i = 0; i < SALES_ORDER_COUNT; i++) {
            Client client = clients.get(RANDOM.nextInt(clients.size()));
            LocalDate orderDate = randomDate(start, today);

            SalesOrder so = new SalesOrder();
            so.setOrderNumber("SO-" + orderDate.getYear() + "-" + String.format("%04d", ++soSeq));
            so.setClient(client);
            so.setOrderDate(orderDate);
            so.setDeliveryAddress(client.getAddress());
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
            tender.setCustomerName(client.getName());
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
