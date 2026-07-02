package com.example.kladdo.config;

import com.example.kladdo.model.PenaltyPeriod;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Role;
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
