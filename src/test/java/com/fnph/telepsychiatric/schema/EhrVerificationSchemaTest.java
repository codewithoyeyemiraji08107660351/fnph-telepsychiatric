package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the EHR verification source that must not regress.
 *
 * This table is the highest-value target in the system. A readable list of EHR
 * numbers with names, dates of birth and phone numbers is a directory of who is
 * a psychiatric patient at this hospital.
 */
class EhrVerificationSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("no plaintext date of birth or phone number is stored in the snapshot")
    void identifiersAreNeverStoredReadable() {
        List<String> plaintext = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'ehr_verification_records' "
                        + "AND COLUMN_NAME IN ('date_of_birth', 'phone_number', 'phone', 'dob')",
                String.class, database());

        assertThat(plaintext)
                .as("matching compares hashes and the portal shows masks; neither needs plaintext")
                .isEmpty();
    }

    @Test
    @DisplayName("hash and mask columns are both present")
    void hashAndMaskColumnsExist() {
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'ehr_verification_records' "
                        + "AND COLUMN_NAME IN ('date_of_birth_hash', 'phone_hash', "
                        + "'date_of_birth_masked', 'phone_masked')",
                String.class, database());

        assertThat(columns).hasSize(4);
    }

    @Test
    @DisplayName("EHR number is unique per import, not globally")
    void ehrNumberIsUniquePerImport() {
        // Globally unique would make versioned snapshots impossible: the second
        // import of the same patient list would collide with the first.
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'ehr_verification_records' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_ehr_records_import_number");
    }

    @Test
    @DisplayName("the same file cannot be imported twice")
    void duplicateFilesAreRefused() {
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'ehr_verification_imports' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_ehr_imports_checksum");
    }

    @Test
    @DisplayName("lookup attempts are append-only")
    void lookupAttemptsAreAppendOnly() {
        // The evidence that someone walked the EHR number range. Editable
        // evidence is not evidence.
        List<String> mutable = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'ehr_lookup_attempts' "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("records cascade when an import is deleted, but patients never do")
    void deletionBehaviourIsCorrect() {
        String recordsRule = jdbc().queryForObject("""
                SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'ehr_verification_records'
                  AND REFERENCED_TABLE_NAME = 'ehr_verification_imports'
                """, String.class, database());

        assertThat(recordsRule).isEqualTo("CASCADE");

        // A patient account must survive its source snapshot being removed.
        // Cascading here would delete real patients when an old import is
        // cleaned up.
        String patientRule = jdbc().queryForObject("""
                SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'patients'
                  AND REFERENCED_TABLE_NAME = 'ehr_verification_imports'
                """, String.class, database());

        assertThat(patientRule).isEqualTo("NO ACTION");
    }

    @Test
    @DisplayName("no centre role can reach the EHR verification source")
    void centreRolesCannotReachTheSnapshot() {
        // A centre patient is never an FNPH patient. A centre account reaching
        // the hospital's record list would collapse that separation entirely.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE' AND p.code LIKE 'ehr%'
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("HIM owns the snapshot, and ICT can upload but not activate")
    void importPermissionsSitWithTheRightRoles() {
        // The person who curates hospital records and the person who
        // administers the platform should not have to be the same account.
        List<String> activate = jdbc().queryForList("""
                SELECT r.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'ehr_import.activate'
                """, String.class);

        assertThat(activate).contains("HIM");
        assertThat(activate)
                .as("deciding which snapshot patients enrol against is a records decision, "
                        + "not a technical one")
                .doesNotContain("ICT_SUPPORT");

        List<String> upload = jdbc().queryForList("""
                SELECT r.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'ehr_import.upload'
                """, String.class);

        assertThat(upload).contains("HIM", "ICT_SUPPORT");
    }
}
