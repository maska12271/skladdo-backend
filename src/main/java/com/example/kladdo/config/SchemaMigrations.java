package com.example.kladdo.config;

import com.example.kladdo.model.CompanyType;
import com.example.kladdo.model.ConnectionStatus;
import com.example.kladdo.model.PenaltyPeriod;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.PlanType;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.SentEmailStatus;
import com.example.kladdo.model.WarehouseMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lightweight schema fix-ups that Hibernate's {@code ddl-auto=update} cannot perform on an existing
 * database. Ordered to run before {@link DataInitializer}.
 *
 * <p>On H2, Hibernate 6.6+ maps each {@code @Enumerated(EnumType.STRING)} column to a native
 * {@code ENUM} column type whose allowed values are fixed at creation. When a value is later added to
 * such an enum (e.g. {@code Role.WAREHOUSE}, {@code PermissionModule.INVENTORY}), {@code update} does
 * not widen the column, so inserts of the new value fail with "Value not permitted for column". We
 * re-state each ENUM column with the full current Java value set. H2 redefines ENUMs by value string
 * (existing rows keep their values), and the statement is idempotent, so this is safe to run every
 * startup. Defensive - it never fails startup.</p>
 */
@Component
@Order(0)
public class SchemaMigrations implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcTemplate jdbc;

    public SchemaMigrations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        widenEnumColumn("APP_USER", "ROLE", Role.class);
        widenEnumColumn("USER_PERMISSION", "MODULE", PermissionModule.class);
        widenEnumColumn("DEFAULT_USER_PERMISSION", "MODULE", PermissionModule.class);
        widenEnumColumn("PRODUCT", "WAREHOUSE_METHOD", WarehouseMethod.class);
        // Per-order penalty-period override column (added with the invoicing feature).
        widenEnumColumn("SALES_ORDER", "PENALTY_PERIOD", PenaltyPeriod.class);
        // Sent-email send outcome (added with the manufacturer-emails feature). A no-op safety net for
        // now (the table is created fresh with all values), but keeps the pattern in place should a
        // value ever be added to SentEmailStatus later.
        widenEnumColumn("SENT_EMAIL", "STATUS", SentEmailStatus.class);
        // The warehouse-partner rework replaced the two-way request handshake with a one-way code, so the
        // columns behind it are gone. They have to be dropped rather than left alone: several were NOT
        // NULL, which would reject every new connection row. Runs before the ENUM re-statement below,
        // which narrows STATUS now that PENDING no longer exists.
        adoptWarehouseAccountType();
        retireHandshakeColumns();
        widenEnumColumn("WAREHOUSE_CONNECTION", "STATUS", ConnectionStatus.class);

        // The subscription's plan. PlanType gained WAREHOUSE (the free tier every warehouse account is on),
        // so without this every warehouse signup fails on an existing database with "Value not permitted
        // for column" - surfacing as a 409 from the constraint handler, not a validation error. Legacy FREE
        // rows are retired first: FREE was removed from PlanType long ago but lingers in the column's value
        // list, and a row still holding it would make the re-statement below fail.
        retireLegacyFreePlan();
        widenEnumColumn("COMPANY_SUBSCRIPTION", "PLAN", PlanType.class);

        // These columns were mapped as globally unique, but the data is tenant-scoped: with more than
        // one company, two companies must be able to reuse the same code/name/number (e.g. each
        // company's own SO-000001, or the same product SKU). Re-scope each unique to (company_id, col).
        scopeUniqueToCompany("CLIENT", "REGISTRATION_CODE");
        scopeUniqueToCompany("PRODUCT", "SKU");
        scopeUniqueToCompany("MANUFACTURER", "NAME");
        scopeUniqueToCompany("CATEGORY", "NAME");
        scopeUniqueToCompany("PARTNER_CATEGORY", "NAME");
        scopeUniqueToCompany("SALES_ORDER", "ORDER_NUMBER");
        scopeUniqueToCompany("PURCHASE_ORDER", "ORDER_NUMBER");
        scopeUniqueToCompany("INVOICE", "INVOICE_NUMBER");

        // SENT_EMAIL.template_id is a plain, informational id (see SentEmail#templateId), not a live FK:
        // a sent email is an immutable audit record and templates must stay deletable. An early build
        // modelled it as a @ManyToOne, which would have created a foreign key that blocks deleting any
        // template ever used. Drop it if present so template deletion works regardless of build history.
        dropForeignKeysOn("SENT_EMAIL", "TEMPLATE_ID");
    }

    /**
     * Moves any subscription still on the removed {@code FREE} tier onto {@link PlanType#STARTER}, so the
     * ENUM re-statement that follows - which no longer lists FREE - is not rejected by an existing row.
     *
     * <p>The comparison casts the column to varchar deliberately. Written as {@code PLAN = 'FREE'}, H2
     * coerces the literal into the column's ENUM type, which throws as soon as FREE is no longer one of its
     * values: the statement would then fail on <em>every</em> startup after the first, logging a warning
     * for work it has already done. Casting compares plain strings, so it simply matches nothing.</p>
     */
    private void retireLegacyFreePlan() {
        try {
            if (!columnExists("COMPANY_SUBSCRIPTION", "PLAN")) {
                return;
            }
            int moved = jdbc.update(
                    "UPDATE COMPANY_SUBSCRIPTION SET PLAN = 'STARTER' WHERE CAST(PLAN AS VARCHAR) = 'FREE'");
            if (moved > 0) {
                log.info("Moved {} subscription(s) off the removed FREE plan onto STARTER.", moved);
            }
        } catch (Exception e) {
            log.warn("Could not retire legacy FREE subscriptions: {}", e.getMessage());
        }
    }

    /**
     * Carries the old reversible {@code warehouse_operator} flag into {@link CompanyType}, then drops it.
     *
     * <p>Without this every company that already ran warehouses for others silently reads as
     * {@code BUSINESS} - the new column arrives null, and null means business - so the separation would be
     * off for exactly the accounts it exists for. The demo seeder cannot cover this either: it is keyed on
     * the logistics owner already existing and returns early on any database that has one.</p>
     */
    private void adoptWarehouseAccountType() {
        try {
            if (!columnExists("COMPANY", "WAREHOUSE_OPERATOR") || !columnExists("COMPANY", "TYPE")) {
                return;
            }
            int adopted = jdbc.update(
                    "UPDATE COMPANY SET TYPE = 'WAREHOUSE' WHERE WAREHOUSE_OPERATOR = TRUE AND TYPE IS NULL");
            // Everyone else is a business, stated rather than left null so the column reads the same as a
            // freshly registered company's.
            jdbc.update("UPDATE COMPANY SET TYPE = 'BUSINESS' WHERE TYPE IS NULL");
            jdbc.execute("ALTER TABLE COMPANY DROP COLUMN IF EXISTS WAREHOUSE_OPERATOR");
            log.info("Adopted the account type for {} warehouse operator(s).", adopted);
        } catch (Exception e) {
            log.warn("Could not adopt the warehouse account type: {}", e.getMessage());
        }
    }

    /**
     * Retires the columns left over from the two-way connection handshake (see
     * {@link com.example.kladdo.model.WarehouseConnection}).
     *
     * <p>Order matters. The operator side is carried over first - {@code provider_company_id} and
     * {@code warehouse_company_id} mean the same thing, so existing connections keep working instead of
     * being orphaned - and any row still awaiting an answer is settled as revoked, because under the
     * one-way model there is nothing left that could accept it. Only then are the old columns dropped.</p>
     *
     * <p>Idempotent and defensive, like everything here: a fresh schema has none of these columns, a second
     * run finds nothing to do, and any failure is logged without aborting startup.</p>
     */
    private void retireHandshakeColumns() {
        try {
            if (!tableExists("WAREHOUSE_CONNECTION")) {
                return;
            }
            ensureConnectionOperatorColumn();

            // No handshake exists any more, so a request nobody can answer is settled rather than left to
            // read as live access. Unguarded by the column swap above: STATUS is narrowed to the current
            // enum right after this, which a leftover PENDING row would defeat.
            jdbc.update("UPDATE WAREHOUSE_CONNECTION SET STATUS = 'REVOKED' WHERE STATUS = 'PENDING'");

            // The warehouse each live connection already covered becomes its first assignment. The client
            // had agreed to exactly that one, so carrying it over keeps a working partner working; without
            // it an upgrade would quietly cut access until somebody noticed and re-picked it.
            if (columnExists("WAREHOUSE_CONNECTION", "CLIENT_WAREHOUSE_ID")) {
                jdbc.update("INSERT INTO CONNECTION_WAREHOUSE (CONNECTION_ID, WAREHOUSE_ID) "
                        + "SELECT ID, CLIENT_WAREHOUSE_ID FROM WAREHOUSE_CONNECTION "
                        + "WHERE STATUS = 'ACTIVE' AND CLIENT_WAREHOUSE_ID IS NOT NULL");
            }

            for (String column : List.of("PROVIDER_WAREHOUSE_ID", "CLIENT_WAREHOUSE_ID",
                    "INITIATED_BY", "ADOPTED_EXISTING_WAREHOUSE", "REQUESTED_BY_USER_ID", "RESPONDED_AT")) {
                jdbc.execute("ALTER TABLE WAREHOUSE_CONNECTION DROP COLUMN IF EXISTS " + column);
            }
            for (String column : List.of("CONNECT_CODE", "PROVIDER_COMPANY_ID", "SOURCE_WAREHOUSE_ID")) {
                jdbc.execute("ALTER TABLE WAREHOUSE DROP COLUMN IF EXISTS " + column);
            }
            jdbc.execute("ALTER TABLE COMPANY DROP COLUMN IF EXISTS CONNECT_CODE");
        } catch (Exception e) {
            log.warn("Could not retire the warehouse-partner handshake columns: {}", e.getMessage());
        }
    }

    /**
     * Guarantees {@code WAREHOUSE_CONNECTION.WAREHOUSE_COMPANY_ID} exists, whatever state the table is in.
     *
     * <p>Hibernate cannot create it on an upgrade: the column is {@code NOT NULL}, and {@code ddl-auto}
     * cannot add a NOT NULL column to a table that already has rows - it logs the failure and carries on.
     * So there are three cases, and the third is the reason this is defensive rather than a one-liner:</p>
     *
     * <ul>
     *   <li>already there (a fresh schema, or a previous run) - nothing to do;</li>
     *   <li>the old {@code PROVIDER_COMPANY_ID} is still present - <strong>rename</strong> it, which keeps
     *       both the data and the constraint;</li>
     *   <li><strong>neither</strong> - an early build of this migration dropped the old column without
     *       renaming it, leaving a table the entity cannot be read from at all (every connection query
     *       fails, which takes login and the company switcher down with it). The column is re-added and the
     *       rows that can no longer name an operator are removed, because nothing can recover which company
     *       they pointed at.</li>
     * </ul>
     */
    private void ensureConnectionOperatorColumn() {
        if (columnExists("WAREHOUSE_CONNECTION", "WAREHOUSE_COMPANY_ID")) {
            return;
        }
        if (columnExists("WAREHOUSE_CONNECTION", "PROVIDER_COMPANY_ID")) {
            jdbc.execute("ALTER TABLE WAREHOUSE_CONNECTION "
                    + "ALTER COLUMN PROVIDER_COMPANY_ID RENAME TO WAREHOUSE_COMPANY_ID");
            log.info("Renamed WAREHOUSE_CONNECTION.PROVIDER_COMPANY_ID to WAREHOUSE_COMPANY_ID.");
            return;
        }
        jdbc.execute("ALTER TABLE WAREHOUSE_CONNECTION ADD COLUMN WAREHOUSE_COMPANY_ID BIGINT");
        int orphaned = jdbc.update("DELETE FROM WAREHOUSE_CONNECTION WHERE WAREHOUSE_COMPANY_ID IS NULL");
        log.warn("WAREHOUSE_CONNECTION had lost its operator column; re-added it and dropped {} "
                + "unrecoverable connection(s). Affected partners must be reconnected with a new code.",
                orphaned);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?", Integer.class, table);
        return count != null && count > 0;
    }

    /**
     * Drops any foreign-key (referential) constraint on {@code table.column}. Idempotent and defensive: a
     * fresh schema has none, and any failure is logged without aborting startup.
     */
    private void dropForeignKeysOn(String table, String column) {
        try {
            if (!columnExists(table, column)) {
                return;
            }
            List<String> constraintNames = jdbc.queryForList(
                    "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                            + "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu "
                            + "  ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_NAME = kcu.TABLE_NAME "
                            + "WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY' AND kcu.COLUMN_NAME = ?",
                    String.class, table, column);
            for (String name : constraintNames) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("Dropped stale foreign key {} on {}.{}.", name, table, column);
            }
        } catch (Exception e) {
            log.warn("Could not drop foreign key on {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Replaces a global single-column unique constraint on {@code table.column} with one scoped to
     * {@code (company_id, column)}. The composite index is created first (it's strictly weaker than the
     * global one, so existing valid data always satisfies it), then the original Hibernate-generated
     * unique constraint — whose name is random — is looked up and dropped. Idempotent and defensive: a
     * second run finds nothing to drop, and any failure is logged without aborting startup.
     */
    private void scopeUniqueToCompany(String table, String column) {
        try {
            if (!columnExists(table, "COMPANY_ID") || !columnExists(table, column)) {
                return; // Table/column not present (older or partial schema) — nothing to do.
            }
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS UQ_" + table + "_" + column + "_COMPANY"
                    + " ON " + table + " (COMPANY_ID, " + column + ")");

            // Find the global single-column UNIQUE constraint(s) on this column and drop them.
            List<String> constraintNames = jdbc.queryForList(
                    "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                            + "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu "
                            + "  ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_NAME = kcu.TABLE_NAME "
                            + "WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'UNIQUE' "
                            + "GROUP BY tc.CONSTRAINT_NAME HAVING COUNT(*) = 1 AND MAX(kcu.COLUMN_NAME) = ?",
                    String.class, table, column);
            for (String name : constraintNames) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("Scoped unique {}.{} to company (dropped global constraint {}).", table, column, name);
            }
        } catch (Exception e) {
            log.warn("Could not scope unique {}.{} to company: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    private void widenEnumColumn(String table, String column, Class<? extends Enum<?>> enumType) {
        try {
            if (!isEnumColumn(table, column)) {
                return; // Column absent or not a native ENUM (e.g. mapped as varchar) - nothing to do.
            }
            String values = Arrays.stream(enumType.getEnumConstants())
                    .map(e -> "'" + e.name() + "'")
                    .collect(Collectors.joining(", "));
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " ENUM(" + values + ")");
            log.info("Ensured {}.{} ENUM accepts all {} values.", table, column, enumType.getSimpleName());
        } catch (Exception e) {
            log.warn("Could not widen ENUM column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean isEnumColumn(String table, String column) {
        List<String> types = jdbc.queryForList(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
        return !types.isEmpty() && "ENUM".equalsIgnoreCase(types.get(0));
    }
}
