package com.example.skladdo.config;

import com.example.skladdo.model.NotificationType;
import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.PlanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lightweight schema fix-ups that Hibernate's {@code ddl-auto=update} cannot perform on an existing
 * database. Ordered to run before {@link DataInitializer}.
 *
 * <p>Every check below reads Postgres's {@code INFORMATION_SCHEMA} case-insensitively: unquoted
 * identifiers are folded to lowercase in the catalog, but comparing a bind parameter against a catalog
 * value is a plain string comparison that doesn't get that folding, so an uppercase literal would
 * silently match nothing. Each fix-up is idempotent and defensive - any failure is logged without
 * aborting startup.</p>
 */
@Component
@Order(0)
public class SchemaMigrations implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcTemplate jdbc;
    private final S3Client s3Client;
    private final String bucket;
    private final String uploadDirPath;

    public SchemaMigrations(JdbcTemplate jdbc, S3Client s3Client, StorageProperties storageProperties,
                            @Value("${app.upload.dir:}") String uploadDirPath) {
        this.jdbc = jdbc;
        this.s3Client = s3Client;
        this.bucket = storageProperties.getBucket();
        this.uploadDirPath = uploadDirPath;
    }

    @Override
    public void run(String... args) {
        // The warehouse-partner rework replaced the two-way request handshake with a one-way code, so the
        // columns behind it are gone. They have to be dropped rather than left alone: several were NOT
        // NULL, which would reject every new connection row.
        adoptWarehouseAccountType();
        retireHandshakeColumns();

        // The subscription's plan. PlanType gained WAREHOUSE (the free tier every warehouse account is
        // on). Legacy FREE rows are retired: FREE was removed from PlanType long ago but a row can still
        // hold the old value.
        retireLegacyFreePlan();

        // The admin panel reports how many companies signed up recently, which needs a creation date the
        // COMPANY table never carried.
        backfillCompanyCreatedAt();

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

        // A sales order line now sells either a product or a service, so its product is optional. The
        // column was created NOT NULL and ddl-auto=update only ever adds - it will not relax an existing
        // constraint - so on any populated database every service line would be rejected by the database
        // itself, long after the service layer had accepted it.
        dropNotNull("SALES_ORDER_ITEM", "PRODUCT_ID");

        // Deleting a category now un-files the products in it rather than refusing while any exist, so the
        // column has to accept null. Same reason as above: ddl-auto=update only ever adds, so on a
        // populated database the delete would still be rejected by the database itself.
        dropNotNull("PRODUCT", "CATEGORY_ID");

        // A service carried a unit ("hour", "visit") for parity with a product, but it never reached an
        // order line or an invoice, so it described nothing anyone could see. Dropped rather than left
        // behind, so the table does not disagree with the entity.
        dropColumn("SERVICE", "UNIT");

        // A client's single CONTACT_PERSON string became PARTNER_CONTACT rows, so a partner can have as
        // many named people as it really has, each with a position and an address. The names already on
        // record are moved across before the column goes.
        migrateClientContactPersons();
        dropColumn("CLIENT", "CONTACT_PERSON");

        // SENT_EMAIL.template_id is a plain, informational id (see SentEmail#templateId), not a live FK:
        // a sent email is an immutable audit record and templates must stay deletable. An early build
        // modelled it as a @ManyToOne, which would have created a foreign key that blocks deleting any
        // template ever used. Drop it if present so template deletion works regardless of build history.
        dropForeignKeysOn("SENT_EMAIL", "TEMPLATE_ID");

        // Hibernate renders an @Enumerated(STRING) column as a CHECK constraint listing the values that
        // existed when the table was created, and ddl-auto=update never widens it. So every enum value
        // added since then is rejected by the database with a 23514, surfacing as an unhelpful "this
        // action conflicts with existing data" 409 long after the service layer accepted the row.
        //
        // PermissionModule gained SERVICES and SERVICE_CATEGORIES with the services catalogue, which made
        // creating ANY user in a restricted role fail: the default-permission rows include one per module.
        // Add a line here whenever an enum behind a persisted column gains a value.
        widenEnumCheck("USER_PERMISSION", "MODULE", PermissionModule.class);
        widenEnumCheck("DEFAULT_USER_PERMISSION", "MODULE", PermissionModule.class);
        // NotificationType gained USER_JOINED with self-service invitations; without this, accepting an
        // invitation would 409 on the notification written to tell the company about it.
        widenEnumCheck("NOTIFICATION", "TYPE", NotificationType.class);

        // Deleting a user releases its email address so the same person can be invited back. Accounts
        // retired by the build before that rule are still sitting on theirs, which is exactly the state
        // the rule exists to prevent.
        releaseDeletedUserEmails();

        // The switch to S3 storage: LOGO_URL/INVOICE_FILE_URL/PRODUCT_IMAGE.IMAGE_URL held local
        // /uploads/<file> paths; the entities now hold bare S3 keys in a same-shaped sibling column
        // instead (added automatically by ddl-auto=update, alongside the old one).
        migrateUploadsToS3();
    }

    /**
     * Gives companies that predate {@code COMPANY.CREATED_AT} the best creation date actually on record:
     * the moment their subscription row was written.
     *
     * <p>Deliberately partial. A company with no subscription is left {@code null} rather than stamped with
     * "now", because a made-up date is worse than an absent one here - every legacy company would surface
     * as a brand-new signup in the admin panel's "new companies this month" figure, which is precisely the
     * number that must not lie. The panel renders a null as unknown, and the recency counts exclude it.</p>
     *
     * <p>Idempotent: only ever fills a null, so a second run finds nothing to do.</p>
     */
    private void backfillCompanyCreatedAt() {
        try {
            if (!columnExists("COMPANY", "CREATED_AT") || !columnExists("COMPANY_SUBSCRIPTION", "CREATED_AT")) {
                return;
            }
            int filled = jdbc.update("""
                    UPDATE COMPANY c SET CREATED_AT = (
                        SELECT MIN(s.CREATED_AT) FROM COMPANY_SUBSCRIPTION s WHERE s.COMPANY_ID = c.ID
                    )
                    WHERE c.CREATED_AT IS NULL
                      AND EXISTS (SELECT 1 FROM COMPANY_SUBSCRIPTION s2
                                  WHERE s2.COMPANY_ID = c.ID AND s2.CREATED_AT IS NOT NULL)
                    """);
            if (filled > 0) {
                log.info("Backfilled the creation date of {} company/companies from their subscription.", filled);
            }
        } catch (Exception e) {
            log.warn("Could not backfill COMPANY.CREATED_AT: {}", e.getMessage());
        }
    }

    /**
     * Moves any subscription still on the removed {@code FREE} tier onto {@link PlanType#STARTER}. FREE
     * was removed from {@link PlanType} long ago but a row can still hold the old value.
     */
    private void retireLegacyFreePlan() {
        try {
            if (!columnExists("COMPANY_SUBSCRIPTION", "PLAN")) {
                return;
            }
            int moved = jdbc.update("UPDATE COMPANY_SUBSCRIPTION SET PLAN = 'STARTER' WHERE PLAN = 'FREE'");
            if (moved > 0) {
                log.info("Moved {} subscription(s) off the removed FREE plan onto STARTER.", moved);
            }
        } catch (Exception e) {
            log.warn("Could not retire legacy FREE subscriptions: {}", e.getMessage());
        }
    }

    /**
     * Turns every non-blank {@code CLIENT.CONTACT_PERSON} into a {@link com.example.skladdo.model.PartnerContact}
     * row, so no name recorded under the old single-string field is lost when the column is dropped.
     *
     * <p>The name is all there is to carry: the old field held a name and nothing else - no position, no
     * address of their own - so the new row gets a name and two nulls. That is not a downgrade, it is
     * exactly what was known, now in a shape that can hold more.</p>
     *
     * <p>Idempotent by construction: it only inserts where the client has no contact rows at all, so a
     * second run finds nothing to do, and a client whose contacts have since been edited (or deliberately
     * emptied and then re-populated) is never given its old string back.</p>
     */
    private void migrateClientContactPersons() {
        try {
            if (!columnExists("CLIENT", "CONTACT_PERSON") || !tableExists("PARTNER_CONTACT")) {
                return;
            }
            int moved = jdbc.update("""
                    INSERT INTO PARTNER_CONTACT (COMPANY_ID, CLIENT_ID, NAME)
                    SELECT c.COMPANY_ID, c.ID, TRIM(c.CONTACT_PERSON)
                    FROM CLIENT c
                    WHERE c.CONTACT_PERSON IS NOT NULL
                      AND TRIM(c.CONTACT_PERSON) <> ''
                      AND NOT EXISTS (SELECT 1 FROM PARTNER_CONTACT pc WHERE pc.CLIENT_ID = c.ID)
                    """);
            if (moved > 0) {
                log.info("Moved {} client contact person(s) into PARTNER_CONTACT.", moved);
            }
        } catch (Exception e) {
            log.warn("Could not migrate CLIENT.CONTACT_PERSON into PARTNER_CONTACT: {}", e.getMessage());
        }
    }

    /**
     * Migrates the three columns that held local {@code /uploads/<file>} paths to the bare S3 keys the
     * app now expects (see {@link com.example.skladdo.service.StorageService}).
     */
    private void migrateUploadsToS3() {
        migrateUploadColumn("COMPANY_SETTINGS", "LOGO_URL", "LOGO_KEY", "images");
        migrateUploadColumn("PURCHASE_ORDER", "INVOICE_FILE_URL", "INVOICE_FILE_KEY", "documents");
        migrateUploadColumn("PRODUCT_IMAGE", "IMAGE_URL", "IMAGE_KEY", "images");
    }

    /**
     * For one column: copies any value still sitting in the old column into its new sibling (added
     * automatically by {@code ddl-auto=update}) and drops the old column - then, independently, uploads
     * the file behind every legacy-shaped value still found in the new column to S3 (from
     * {@code app.upload.dir}, kept only for this transitional read) and rewrites it to a bare key.
     *
     * <p>The two steps are guarded separately. The copy-and-drop runs once (guarded on the old column
     * still existing); the upload-and-rewrite instead re-scans for any remaining {@code /uploads/}-shaped
     * value on every startup, so a file that was momentarily missing (e.g. a slow-mounting volume) gets
     * retried on the next restart instead of being stuck forever.</p>
     */
    private void migrateUploadColumn(String table, String oldColumn, String newColumn, String category) {
        try {
            if (columnExists(table, oldColumn)) {
                int copied = jdbc.update("UPDATE " + table + " SET " + newColumn + " = " + oldColumn
                        + " WHERE " + newColumn + " IS NULL AND " + oldColumn + " IS NOT NULL");
                jdbc.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS " + oldColumn);
                if (copied > 0) {
                    log.info("Migrated {} legacy upload path(s) from {}.{} to {}.{} and dropped the old column.",
                            copied, table, oldColumn, table, newColumn);
                }
            }
        } catch (Exception e) {
            log.warn("Could not copy {}.{} to {}: {}", table, oldColumn, newColumn, e.getMessage());
        }

        try {
            List<String> legacyValues = jdbc.queryForList(
                    "SELECT DISTINCT " + newColumn + " FROM " + table + " WHERE " + newColumn + " LIKE '/uploads/%'",
                    String.class);
            for (String legacyValue : legacyValues) {
                try {
                    uploadLegacyFile(legacyValue, category);
                } catch (Exception e) {
                    log.warn("Could not upload legacy file '{}' to S3: {}", legacyValue, e.getMessage());
                }
            }
            int rewritten = jdbc.update("UPDATE " + table + " SET " + newColumn + " = '" + category
                    + "/' || SUBSTRING(" + newColumn + " FROM 10) WHERE " + newColumn + " LIKE '/uploads/%'");
            if (rewritten > 0) {
                log.info("Rewrote {} {}.{} value(s) from a local path to an S3 key.", rewritten, table, newColumn);
            }
        } catch (Exception e) {
            log.warn("Could not migrate legacy files in {}.{} to S3: {}", table, newColumn, e.getMessage());
        }
    }

    /** Uploads the local file behind one legacy {@code /uploads/<file>} value to S3, same filename. */
    private void uploadLegacyFile(String legacyPath, String category) throws IOException {
        if (uploadDirPath.isBlank()) {
            return;
        }
        String filename = legacyPath.substring(legacyPath.lastIndexOf('/') + 1);
        Path localFile = Paths.get(uploadDirPath).toAbsolutePath().normalize().resolve(filename);
        if (!Files.exists(localFile)) {
            log.warn("Legacy upload '{}' has no local file at {}; its S3 key will not resolve.", legacyPath, localFile);
            return;
        }
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(category + "/" + filename)
                        .contentType(Files.probeContentType(localFile))
                        .build(),
                RequestBody.fromFile(localFile));
    }

    /**
     * Carries the old reversible {@code warehouse_operator} flag into
     * {@link com.example.skladdo.model.CompanyType}, then drops it.
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
     * {@link com.example.skladdo.model.WarehouseConnection}).
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
                    + "RENAME COLUMN PROVIDER_COMPANY_ID TO WAREHOUSE_COMPANY_ID");
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
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)",
                Integer.class, table);
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
                            + "WHERE UPPER(tc.TABLE_NAME) = UPPER(?) AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY' "
                            + "AND UPPER(kcu.COLUMN_NAME) = UPPER(?)",
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
                            + "WHERE UPPER(tc.TABLE_NAME) = UPPER(?) AND tc.CONSTRAINT_TYPE = 'UNIQUE' "
                            + "GROUP BY tc.CONSTRAINT_NAME HAVING COUNT(*) = 1 AND UPPER(MAX(kcu.COLUMN_NAME)) = UPPER(?)",
                    String.class, table, column);
            for (String name : constraintNames) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("Scoped unique {}.{} to company (dropped global constraint {}).", table, column, name);
            }
        } catch (Exception e) {
            log.warn("Could not scope unique {}.{} to company: {}", table, column, e.getMessage());
        }
    }

    /** Removes a column the entity no longer maps. Idempotent and defensive, like everything here. */
    private void dropColumn(String table, String column) {
        try {
            if (!columnExists(table, column)) {
                return;
            }
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS " + column);
            log.info("Dropped retired column {}.{}.", table, column);
        } catch (Exception e) {
            log.warn("Could not drop {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Relaxes a {@code NOT NULL} column to nullable. Idempotent (dropping a constraint that is not there
     * is a no-op in Postgres) and defensive: a missing table/column is skipped and any failure is logged
     * without aborting startup.
     */
    private void dropNotNull(String table, String column) {
        try {
            if (!columnExists(table, column)) {
                return;
            }
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
        } catch (Exception e) {
            log.warn("Could not make {}.{} nullable: {}", table, column, e.getMessage());
        }
    }

    /**
     * Rebuilds the CHECK constraint behind an {@code @Enumerated(STRING)} column so it accepts every value
     * the Java enum currently declares.
     *
     * <p>Hibernate writes that constraint once, when it creates the table, and {@code ddl-auto=update}
     * only ever <em>adds</em> - it will not widen an existing constraint. An enum value added later is
     * therefore accepted by the application and refused by the database, which is both invisible in code
     * review and hard to read at runtime: the driver raises SQLSTATE 23514 and the caller is told the row
     * "conflicts with existing data".</p>
     *
     * <p>Rewritten only when it is actually out of date, so an up-to-date database is left untouched and
     * says nothing in the log. The constraint is looked up by definition rather than by Hibernate's
     * {@code <table>_<column>_check} naming, so one created under a different name is still replaced
     * instead of being left behind to reject the same rows.</p>
     */
    private void widenEnumCheck(String table, String column, Class<? extends Enum<?>> type) {
        try {
            if (!columnExists(table, column)) {
                return;
            }
            List<String> wanted = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
            List<String> stale = jdbc.queryForList(
                    "SELECT con.conname FROM pg_constraint con "
                            + "JOIN pg_class c ON c.oid = con.conrelid "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE con.contype = 'c' AND n.nspname = current_schema() "
                            + "AND LOWER(c.relname) = LOWER(?) "
                            + "AND pg_get_constraintdef(con.oid) ILIKE ?",
                    String.class, table, "%(" + column.toLowerCase() + ")%");

            // Nothing to do when every value the enum knows is already listed by every constraint found.
            boolean current = !stale.isEmpty() && stale.stream().allMatch(name -> {
                String definition = jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                        String.class, name);
                return definition != null && wanted.stream().allMatch(v -> definition.contains("'" + v + "'"));
            });
            if (current) {
                return;
            }

            for (String name : stale) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + name);
            }
            String values = wanted.stream().map(v -> "'" + v + "'").collect(Collectors.joining(","));
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + table.toLowerCase() + "_"
                    + column.toLowerCase() + "_check CHECK (" + column + " IN (" + values + "))");
            log.info("Widened the {}.{} check constraint to the {} values {} currently declares.",
                    table, column, wanted.size(), type.getSimpleName());
        } catch (Exception e) {
            log.warn("Could not widen the check constraint on {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Gives retired accounts the tombstone address that {@code UserService.delete} now assigns, so the
     * address they were holding becomes available again.
     *
     * <p>{@code APP_USER.EMAIL} is unique, so a deleted row keeping its address makes that person
     * impossible to invite back - and "deleted" quietly meaning "this address is burned forever" is not
     * something anyone would guess. Rows retired before the release rule existed are still like that;
     * this brings them into line. The address is not lost: the audit entry written at deletion holds it.
     *
     * <p>Idempotent - a row already on a tombstone is skipped by the {@code NOT LIKE}.</p>
     */
    private void releaseDeletedUserEmails() {
        try {
            if (!columnExists("APP_USER", "DELETED_AT")) {
                return;
            }
            int freed = jdbc.update("""
                    UPDATE APP_USER
                    SET EMAIL = 'deleted+' || ID || '@removed.invalid'
                    WHERE DELETED_AT IS NOT NULL AND EMAIL NOT LIKE 'deleted+%@removed.invalid'
                    """);
            if (freed > 0) {
                log.info("Released the email addresses of {} previously deleted account(s).", freed);
            }
        } catch (Exception e) {
            log.warn("Could not release deleted accounts' email addresses: {}", e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)",
                Integer.class, table, column);
        return count != null && count > 0;
    }
}
