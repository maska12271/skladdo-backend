package com.example.skladdo.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * How much room one company's rows take in the database, for the platform admin panel.
 *
 * <p>Skladdo is one schema shared by every tenant, so Postgres's own per-table sizes cannot answer "how
 * big is this customer" — {@code pg_total_relation_size} describes the whole table, not a slice of it.
 * This sums {@code pg_column_size} over the rows belonging to one company instead, which is the real
 * width of each row's data. It excludes per-page and index overhead, so it reads slightly under the true
 * on-disk figure rather than inventing one.</p>
 *
 * <p>Plain JDBC, not JPA: this is SQL about the physical layout, which is exactly what an ORM abstracts
 * away.</p>
 *
 * <p>Not cheap — a sequential scan per table — so it belongs on one company's detail page and never in a
 * list that renders a row per tenant.</p>
 */
@Repository
public class TenantFootprintRepository {

    private static final Logger log = LoggerFactory.getLogger(TenantFootprintRepository.class);

    private final JdbcTemplate jdbc;

    public TenantFootprintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every table in the current schema carrying a {@code company_id}.
     *
     * <p>Asked of the catalog rather than written down. A hand-kept list is wrong the moment a feature adds
     * a table and nobody remembers this file - and wrong quietly, since a missing table just makes the
     * total smaller. It was already wrong when first written: several tables here are named in the plural
     * and one had no such column at all.</p>
     */
    private List<String> tenantTables() {
        return jdbc.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'company_id'
                WHERE c.relkind = 'r' AND n.nspname = current_schema()
                ORDER BY c.relname
                """, String.class);
    }

    /** Total bytes of row data this company holds, across every tenant-scoped table. */
    public long rowBytesFor(Long companyId) {
        long total = 0;
        for (String table : tenantTables()) {
            try {
                // The name comes from the catalog, not from a caller, so interpolating it is safe - and
                // necessary, since a table name cannot be a bind parameter.
                Long bytes = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(pg_column_size(t.*)), 0) FROM " + table + " t WHERE t.company_id = ?",
                        Long.class, companyId);
                total += bytes == null ? 0 : bytes;
            } catch (Exception e) {
                // One unreadable table must not cost the operator the whole figure.
                log.debug("Skipped {} while sizing company {}: {}", table, companyId, e.getMessage());
            }
        }
        return total;
    }
}
