package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the schema conventions against the live migrated schema.
 *
 * A convention nobody checks is a convention that decays. These assertions run
 * on every build, so a future migration that forgets the audit columns, uses
 * MyISAM, picks the wrong collation, or adds a mutable column to an append-only
 * table fails the build rather than being discovered a year later during an
 * audit.
 */
class SchemaConventionsTest extends AbstractMigratedDatabaseTest {

    /** Tables Flyway owns, which are not ours to shape. */
    private static final Set<String> EXCLUDED = Set.of("flyway_schema_history");

    /** Append-only by design: no updates, therefore no update columns. */
    private static final Set<String> APPEND_ONLY = Set.of(
            "audit_logs", "wallet_transactions", "login_attempts", "configuration_changes");

    /**
     * Association tables. Composite primary key, no surrogate id and no
     * public_id, because the row has no identity of its own: it IS the pair,
     * and the pair is its natural key.
     *
     * A named category, not an ad-hoc exception. Anything added here needs the
     * same justification.
     */
    private static final Set<String> ASSOCIATION = Set.of("role_permission", "user_role");

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    private List<String> businessTables() {
        return jdbc().queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
                        + "ORDER BY TABLE_NAME",
                String.class, database())
                .stream()
                .filter(t -> !EXCLUDED.contains(t))
                .filter(t -> !ASSOCIATION.contains(t))
                .toList();
    }

    @Test
    @DisplayName("every table carries id, public_id, created_at and created_by")
    void everyTableHasTheConventionColumns() {
        List<String> offenders = jdbc().queryForList("""
                SELECT t.TABLE_NAME
                FROM information_schema.TABLES t
                JOIN information_schema.COLUMNS c
                  ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE'
                  AND t.TABLE_NAME NOT IN ('flyway_schema_history', 'role_permission', 'user_role')
                GROUP BY t.TABLE_NAME
                HAVING SUM(c.COLUMN_NAME = 'id') = 0
                    OR SUM(c.COLUMN_NAME = 'public_id') = 0
                    OR SUM(c.COLUMN_NAME = 'created_at') = 0
                    OR SUM(c.COLUMN_NAME = 'created_by') = 0
                """, String.class, database());

        assertThat(offenders)
                .as("tables missing one or more convention columns")
                .isEmpty();
    }

    @Test
    @DisplayName("mutable tables carry updated_at and updated_by, append-only tables do not")
    void updateColumnsFollowMutability() {
        List<String> withUpdatedAt = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'updated_at'",
                String.class, database());

        assertThat(withUpdatedAt)
                .as("append-only tables must never gain update tracking")
                .doesNotContainAnyElementsOf(APPEND_ONLY);

        List<String> expected = businessTables().stream()
                .filter(t -> !APPEND_ONLY.contains(t)).toList();

        assertThat(withUpdatedAt)
                .as("every mutable table tracks updates")
                .containsAll(expected);
    }

    @Test
    @DisplayName("public_id is unique everywhere it exists")
    void publicIdIsUnique() {
        List<String> withUniqueIndex = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'public_id' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(withUniqueIndex)
                .as("a public identifier that can repeat is not an identifier")
                .containsAll(businessTables());
    }

    @Test
    @DisplayName("public_id is 26 characters, matching the ULID format")
    void publicIdIsUlidWidth() {
        List<String> wrongWidth = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', CHARACTER_MAXIMUM_LENGTH) "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'public_id' "
                        + "AND CHARACTER_MAXIMUM_LENGTH <> 26",
                String.class, database());

        assertThat(wrongWidth).isEmpty();
    }

    @Test
    @DisplayName("association tables carry a composite key and no surrogate id")
    void associationTablesHaveNoSurrogateKey() {
        // The justification for excluding them from the public_id convention is
        // that they have no identity of their own. If one grows a surrogate id,
        // that justification no longer holds and the exclusion has to be
        // revisited rather than silently inherited.
        List<String> withSurrogate = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'id' "
                        + "AND TABLE_NAME IN ('role_permission', 'user_role')",
                String.class, database());

        assertThat(withSurrogate).isEmpty();
    }

    @Test
    @DisplayName("every table is InnoDB")
    void everyTableIsInnoDb() {
        List<String> offenders = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, ' uses ', ENGINE) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB'",
                String.class, database());

        assertThat(offenders)
                .as("MyISAM has no transactions and no foreign keys")
                .isEmpty();
    }

    @Test
    @DisplayName("every table is utf8mb4")
    void everyTableIsUtf8mb4() {
        List<String> offenders = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, ' uses ', TABLE_COLLATION) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
                        + "AND TABLE_COLLATION NOT LIKE 'utf8mb4%'",
                String.class, database());

        assertThat(offenders)
                .as("legacy utf8 is 3-byte and mangles anything outside the BMP")
                .isEmpty();
    }

    @Test
    @DisplayName("timestamps are DATETIME, never TIMESTAMP")
    void timestampsAreDatetime() {
        // TIMESTAMP silently converts on read and write using the session
        // timezone, and tops out in 2038. DATETIME stores exactly what it is
        // given, which is what a UTC-everywhere policy requires.
        List<String> offenders = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND DATA_TYPE = 'timestamp'",
                String.class, database());

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no table stores money as a floating point type")
    void moneyIsNeverFloatingPoint() {
        List<String> offenders = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, ' is ', DATA_TYPE) "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND DATA_TYPE IN ('float', 'double') "
                        + "AND (COLUMN_NAME LIKE '%amount%' OR COLUMN_NAME LIKE '%balance%' "
                        + "     OR COLUMN_NAME LIKE '%fee%' OR COLUMN_NAME LIKE '%price%' "
                        + "     OR COLUMN_NAME LIKE '%threshold%')",
                String.class, database());

        assertThat(offenders)
                .as("binary floating point cannot represent 0.10 exactly; use DECIMAL")
                .isEmpty();
    }

    @Test
    @DisplayName("every foreign key column is indexed")
    void foreignKeysAreIndexed() {
        // InnoDB creates an index for a foreign key automatically, so this
        // guards against a future migration adding the column and constraint
        // in a way that leaves the child side unindexed and every delete on
        // the parent doing a full scan.
        List<String> offenders = jdbc().queryForList("""
                SELECT CONCAT(k.TABLE_NAME, '.', k.COLUMN_NAME)
                FROM information_schema.KEY_COLUMN_USAGE k
                WHERE k.TABLE_SCHEMA = ?
                  AND k.REFERENCED_TABLE_NAME IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM information_schema.STATISTICS s
                      WHERE s.TABLE_SCHEMA = k.TABLE_SCHEMA
                        AND s.TABLE_NAME = k.TABLE_NAME
                        AND s.COLUMN_NAME = k.COLUMN_NAME
                        AND s.SEQ_IN_INDEX = 1
                  )
                """, String.class, database());

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("every tenant-owned table carries centre_id, with no exceptions")
    void tenantOwnedTablesAreScopable() {
        // A tenant-owned table with no centre_id cannot be filtered at the
        // repository layer, which is where the isolation guarantee has to live.
        // Scoping that depends on a join fails open the first time someone
        // writes a query without it, and it fails silently by returning another
        // centre's clinical records rather than an error.
        //
        // No exception list. An exception here is a table that will eventually
        // leak. The isolation suite proves the filter is applied; this proves
        // applying it is possible.
        List<String> tenantOwned = businessTables().stream()
                .filter(t -> t.startsWith("centre_"))
                .toList();

        List<String> withCentreId = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'centre_id'",
                String.class, database());

        assertThat(withCentreId).containsAll(tenantOwned);
    }

    @Test
    @DisplayName("dual-owned clinical tables carry centre_id so centre rows can be scoped")
    void dualOwnedClinicalTablesAreScopable() {
        // prescriptions, investigations and follow_ups serve both pathways.
        // NULL centre_id means FNPH; a value means Centre. Without the column,
        // a centre query would have to join through centre_patients to know
        // which rows it may see.
        List<String> withCentreId = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'centre_id'",
                String.class, database());

        assertThat(withCentreId)
                .contains("prescriptions", "investigations", "follow_ups", "file_uploads");
    }

    @Test
    @DisplayName("centre_id is indexed everywhere it exists")
    void tenantKeyIsIndexed() {
        // Every tenant-scoped query filters on this column. An unindexed
        // tenant key turns each of them into a full table scan.
        List<String> unindexed = jdbc().queryForList("""
                SELECT DISTINCT c.TABLE_NAME
                FROM information_schema.COLUMNS c
                WHERE c.TABLE_SCHEMA = ? AND c.COLUMN_NAME = 'centre_id'
                  AND NOT EXISTS (
                      SELECT 1 FROM information_schema.STATISTICS s
                      WHERE s.TABLE_SCHEMA = c.TABLE_SCHEMA
                        AND s.TABLE_NAME = c.TABLE_NAME
                        AND s.COLUMN_NAME = 'centre_id'
                        AND s.SEQ_IN_INDEX = 1
                  )
                """, String.class, database());

        assertThat(unindexed).isEmpty();
    }
}
