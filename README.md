# Skladdo Backend

Skladdo is a multi-tenant REST API for wholesale and distribution companies: catalogue, inventory across
warehouses, sales and purchase orders, PDF invoices, tenders, supplier email and the analytics on top of
all of it. It is the Spring Boot half of the product — the companion SPA lives in **`skladdo-frontend`**.

## What it does

- **Multi-tenancy.** Every company-scoped entity carries a Hibernate `@TenantId companyId`; the tenant is
  resolved per request from the JWT (`CompanyTenantIdentifierResolver` + `TenantContext`), so no query has
  to remember to filter by company. Singleton-per-company rows (`CompanySettings`, `CompanySubscription`)
  are created lazily on first access.
- **Two account types.** `CompanyType.BUSINESS` owns the data; `CompanyType.WAREHOUSE` is a logistics
  provider that owns none. A business issues a single-use `ConnectionCode` (48h), the warehouse account
  redeems it, and from then on one login can switch between client companies — `CustomUserDetails
  .getCompanyId()` returns the *active* company, which is why the rest of the services needed no changes.
  Access is limited to the warehouses the client assigns, and `PriceRedactionAdvice` strips monetary
  fields from responses when a connection has `canSeePrices = false`.
- **Authentication & authorization.** JWT (jjwt) auth, `Role` (OWNER / ADMINISTRATOR / USER / WAREHOUSE)
  plus per-user `UserPermission` rows: view / create / edit / delete per `PermissionModule`, enforced on
  controllers with `@perm.canView(auth,'MODULE')`-style SpEL. `LoginRateLimiter` throttles the
  unauthenticated endpoints per account and per IP.
- **Catalogue & inventory.** Products, categories, manufacturers, clients; multiple `Warehouse`s,
  `ProductBatch` lots with expiry, FEFO/FIFO/LIFO stock-out (`WarehouseMethod`), transfers, adjustments,
  a derived stock ledger (`StockLedgerService` — no movement table) and reorder suggestions.
- **Orders & invoicing.** `PurchaseOrder` / `SalesOrder` with status history, per-line picking and goods
  receipt, and `Invoice`s rendered to PDF from Thymeleaf templates via openhtmltopdf, with penalties,
  prepayments and payment terms.
- **Tenders.** Multi-part tenders (`TenderPart`, `TenderRequirement`), participants and per-part winners,
  configurable tender numbering, deadline notifications.
- **Supplier email.** Per-company SMTP (passwords encrypted with AES-256-GCM by `EncryptionService`),
  templates, bulk sends, open tracking via a pixel and reply threading through a Mailgun inbound webhook
  (`reply+{token}@<inbound-domain>`, HMAC-verified).
- **Multi-currency.** Per-transaction currency with base-currency rates, ECB rate warm-up, conversion in
  `MoneyConverter`.
- **Plans & add-ons.** `PlanType` STARTER / BUSINESS / ENTERPRISE with user, manufacturer and product
  caps, plus purchasable add-ons (tenders, manufacturer emails). Warehouse accounts sit on a fourth,
  non-selectable `PlanType.WAREHOUSE` tier at €0. Enforcement is create-time only, in `PlanService`.
  **There is no payment provider wired up yet** — plan changes take effect immediately and are not charged.
- **Audit log & notifications.** `AuditLog` records who did what; `Notification` fans out to the users
  holding the relevant permission, with per-user mutes.
- **Background jobs.** `@EnableScheduling` with a kill switch (`app.scheduling.enabled`): nightly billing
  rollover, ECB rate warm-up, and the overdue-invoice / tender-deadline / low-stock notification producers
  on configurable crons.
- **i18n.** Errors are message keys resolved from `Accept-Language` against
  `messages{,_et,_ru}.properties` — English, Estonian, Russian.
- **Developer tooling.** Local Postgres via `docker-compose.yml`, seeded with a realistic demo dataset by
  `DataInitializer`, and SpringDoc OpenAPI UI.

## Tech Stack

- Java 21, Spring Boot 4 (Web MVC, Data JPA, Security, Validation, Mail, Thymeleaf)
- PostgreSQL everywhere — local dev (via `docker-compose.yml`), tests (via Testcontainers) and prod
- jjwt for tokens, openhtmltopdf-pdfbox for invoice PDFs
- SpringDoc OpenAPI, Lombok, Maven wrapper

## Project Structure

```text
src/main/java/com/example/skladdo
├── config/        # DataInitializer, SchemaMigrations, scheduling, CORS, i18n, OpenAPI
├── controller/    # REST controllers (~30, one per feature area)
├── dto/           # Request/response DTOs
├── exception/     # Global exception handling → i18n error keys
├── model/         # JPA entities and enums
├── repository/    # Spring Data JPA repositories
├── security/      # JWT, tenancy, permissions, encryption, rate limiting, price redaction
└── service/       # Business logic
```

### Schema management

There is **no Flyway or Liquibase**: the schema is maintained by `ddl-auto=update` plus a hand-written
`SchemaMigrations` bean that runs first (`@Order(0)`). Two consequences worth knowing before adding a
column:

- New columns must be **nullable wrapper types** with a getter that coalesces `null` → default, because
  `ddl-auto=update` cannot add a `NOT NULL` column to a populated table.
- `SchemaMigrations`'s `INFORMATION_SCHEMA` lookups compare identifiers case-insensitively on purpose:
  Postgres folds unquoted identifiers to lowercase in the catalog, so an uppercase literal bind parameter
  would otherwise silently match nothing.

## Getting Started

### Prerequisites

- JDK 21+
- The bundled Maven wrapper (no separate Maven install needed)
- Docker (runs the local Postgres via `docker-compose.yml`, and the tests' Testcontainers Postgres)

### Run

```bash
docker compose up -d
./mvnw spring-boot:run
```

On Windows:

```bash
mvnw.cmd spring-boot:run
```

The API starts on `http://localhost:8080`. Start the frontend separately (see its README); it expects the
API at that origin by default.

### Demo data & login

`DataInitializer` seeds a full demo company — *Nordic Trade OÜ* — on first start, when the database has no
users yet:

- 20 users with varied permission profiles
- 8 categories, 12 manufacturers, 15 clients, 80 products
- 140 purchase orders, 190 sales orders and 48 tenders with participants
- ~3 years of history, including low-stock items for the dashboard
- *Baltic Logistics OÜ*, a WAREHOUSE account connected to Nordic Trade's Satellite Depot

| Account            | Email                        | Password       |
|--------------------|------------------------------|----------------|
| Owner              | `owner@demo.com`             | `owner123`     |
| Administrator      | `admin@demo.com`             | `admin123`     |
| Staff (USER)       | `*.*@nordictrade.ee`         | `password123`  |
| Warehouse partner  | `owner@balticlogistics.ee`   | `logistics123` |

**Resetting the data:** `docker compose down -v && docker compose up -d`, then start the app again. Disable
seeding entirely with `app.seed.enabled=false`.

### Local database

`docker compose up -d` starts Postgres on `localhost:5432` (db `skladdo`, user/password `skladdo`) — connect
with `psql` or any Postgres client to inspect it directly.

## API

Roughly 30 controllers under `/api`, including `auth`, `users`, `company`, `settings`, `clients`,
`manufacturers`, `categories`, `products`, `warehouses`, `warehouse-partners`, `purchase-orders`,
`sales-orders`, `invoices`, `tenders`, `tender-parts`, `tender-participants`, `emails`, `sent-emails`,
`notifications`, `audit-logs`, `subscription`, `dashboard`, `currencies`, `exchange-rates`, plus the
public `public/register` and `public/password` endpoints and the Mailgun/tracking webhooks.

Interactive docs while the app is running:

```text
http://localhost:8080/swagger-ui.html
```

## Running Tests

```bash
./mvnw test
```

The suite is integration-heavy — tenant isolation, permission boundaries, the order lifecycle, plan
enforcement, warehouse partners, notification fan-out, rate limiting, money and the stock ledger — and
runs against a disposable Postgres started by Testcontainers (`support/TestcontainersConfiguration`), so
it needs Docker running but nothing manually started — it's independent of the dev app / its database.

CI (`.github/workflows/ci.yml`) runs the same command on every push and pull request.

## Configuration

Local defaults live in `application.properties`; `application-prod.properties` is the production profile
and reads **everything** from environment variables with no fallbacks, so a misconfigured deployment
refuses to start rather than booting insecure. The variables it requires:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection |
| `APP_JWT_SECRET` | Token signing key |
| `APP_CORS_ALLOWED_ORIGINS` | Allowed SPA origins |
| `APP_ENCRYPTION_KEY` | AES-256-GCM key for stored SMTP passwords — **cannot be rotated freely** |
| `APP_FRONTEND_BASE_URL` | Origin used to build emailed password links |
| `APP_PUBLIC_BASE_URL` | Internet-facing origin of this API (email tracking pixel) |
| `APP_MAIL_INBOUND_DOMAIN` | Subdomain whose MX points at Mailgun, for reply routing |
| `APP_MAILGUN_WEBHOOK_SIGNING_KEY` | Verifies inbound reply webhooks |

Other useful switches: `app.seed.enabled`, `app.scheduling.enabled`, `app.jobs.*-cron`,
`app.auth.rate-limit.*`, `app.currency.base-default` / `.supported`.

### Docker

The `Dockerfile` builds the jar and runs it on a slim JRE, defaulting to the `prod` profile. Tests are
skipped in the image build — run them locally or in CI.

## Known gaps

- No payment provider: subscriptions and add-ons change immediately and are never charged.
- Plan prices and caps are placeholders.
- `ddl-auto=update` is still in use in production; move to real migrations before it holds data that
  matters.
