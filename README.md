# Kladdo Backend

Kladdo is a Spring Boot REST API for managing companies, clients, products, tenders, and related sales/purchase orders in a small business environment.

It is the backend part of a full‑stack course project and is designed to be used together with the `kladdo-frontend` React application.

## Features

- **Authentication & authorization**
  - Spring Security with JWT-based authentication.
  - `User` and `Role` entities for role-based access control.
- **Company & master data**
  - `Company` entity to group users and business data.
  - Management of `Client`, `Manufacturer`, `Category`, and `Product` records via REST controllers.
- **Orders & inventory**
  - `PurchaseOrder` / `PurchaseOrderItem` for incoming stock.
  - `SalesOrder` / `SalesOrderItem` for outgoing orders.
  - `OrderStatus` enum for tracking lifecycle.
- **Tender management**
  - `Tender`, `TenderParticipant`, and `TenderStatus` to model tenders and participants.
- **Developer tooling**
  - File‑based H2 database (persisted under `./data`) preloaded with a realistic demo dataset, plus the H2 console.
  - SpringDoc OpenAPI UI for interactive API documentation.

## Tech Stack

- Java 21
- Spring Boot 4 (Spring Web MVC, Spring Data JPA, Spring Security, Validation)
- H2 database (runtime)
- JSON Web Tokens (jjwt) for authentication
- SpringDoc OpenAPI for Swagger UI
- Maven wrapper (`mvnw` / `mvnw.cmd`
- Lombok for boilerplate reduction

## Project Structure

```text
src/main/java/com/example/kladdo
├── config/        # General Spring / OpenAPI configuration
├── controller/    # REST controllers (auth, users, clients, tenders, orders, etc.)
├── dto/           # Request/response DTOs
├── exception/     # Global exception handling
├── model/         # JPA entities and enums
├── repository/    # Spring Data JPA repositories
├── security/      # Security config, filters, JWT support
└── service/       # Business logic services
```

Key domain entities:

- `User`, `Role`, `Company`
- `Client`, `Manufacturer`, `Category`, `Product`
- `PurchaseOrder`, `PurchaseOrderItem`, `SalesOrder`, `SalesOrderItem`, `OrderStatus`
- `Tender`, `TenderParticipant`, `TenderStatus`

## Getting Started

### Prerequisites

- JDK 21+
- Maven 3.9+ (or just use the included Maven wrapper)
- IDE such as IntelliJ IDEA / Eclipse (optional)

### Run with Maven wrapper

```bash
# from the project root (where pom.xml is)
./mvnw spring-boot:run
```

On Windows:

```bash
mvnw.cmd spring-boot:run
```

The application will start on:

```text
http://localhost:8080
```

### Demo data & login

The repository ships a **file‑based H2 database** (`data/tenderdb.mv.db`) that is already populated with a
full demo company — *Nordic Trade OÜ* — so everyone who clones the project sees the **same data**:

- 20 users with varied permission profiles
- 8 categories, 12 manufacturers, 15 clients, 80 products
- 140 purchase orders, 190 sales orders (attributed to their authors) and 48 tenders with participants
- ~3 years of history, including low‑stock items for the dashboard

Sign in with any of these accounts:

| Role          | Email                        | Password      |
|---------------|------------------------------|---------------|
| Owner         | `owner@demo.com`             | `owner123`    |
| Administrator | `admin@demo.com`             | `admin123`    |
| Staff (USER)  | `*.*@nordictrade.ee`         | `password123` |

**Resetting the data:** the `DataInitializer` only seeds when the database is empty (it leaves the bundled
data untouched on normal startup). To regenerate from scratch, stop the app, delete `data/tenderdb.mv.db`,
and start it again — it will recreate the demo dataset. Disable seeding entirely with `app.seed.enabled=false`.

### H2 Console

The database is a file‑based H2 store (`jdbc:h2:file:./data/tenderdb`). Check `application.properties` for the
console path and credentials, then open:

```text
http://localhost:8080/h2-console
```

(Adjust URL if you change server port.)

## Frontend

The companion React UI lives in the separate **`kladdo-frontend`** repository. Run the backend first (so the
API is available on `http://localhost:8080`), then start the frontend dev server per its own README.

## API Overview

Controllers (package `controller`) expose REST endpoints around:

- `/api/auth` – authentication (login/register).
- `/api/users` – user and role management.
- `/api/companies` – company-level configuration.
- `/api/clients` – CRUD for clients.
- `/api/manufacturers` – CRUD for manufacturers.
- `/api/categories` – CRUD for product categories.
- `/api/products` – CRUD for products.
- `/api/purchase-orders` – purchase order lifecycle.
- `/api/sales-orders` – sales order lifecycle.
- `/api/tenders` and `/api/tender-participants` – tender management.

Exact paths and request/response models can be explored via the Swagger UI.

### OpenAPI / Swagger UI

When the app is running, open:

```text
http://localhost:8080/swagger-ui.html
```

or the SpringDoc UI path configured in `springdoc-openapi-starter-webmvc-ui` to see the interactive API documentation.

## Running Tests

The project includes test starters for JPA, validation, and MVC:

```bash
./mvnw test
```

or

```bash
mvnw.cmd test
```

## Environment & Configuration

- Default profile uses H2 and local configuration.
- For production, configure an external relational database and update:
  - JDBC URL / credentials
  - JPA `ddl-auto` strategy
- JWT secret, expiration, and security settings should be stored in environment variables or external config, not committed to the repo.

## Roadmap / Ideas

- Per‑company multi‑tenant behavior (one company, many employees).
- More fine‑grained permissions (per action per role).
- Audit fields (`createdBy`, `updatedBy`) and separate audit log table.
- Pagination, sorting, and filtering for all list endpoints.
- Import/export endpoints for bulk data (CSV/XLSX) for entities like clients and products.

---
