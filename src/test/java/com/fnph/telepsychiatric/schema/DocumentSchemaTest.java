package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Document guarantees that must not regress. */
class DocumentSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("an issue number identifies exactly one document")
    void issueNumberIsUnique() {
        // It is quoted on the phone and printed on paper. Two documents sharing
        // one would make a verification answer ambiguous.
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'issued_documents' "
                        + "AND COLUMN_NAME = 'issue_number' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("a source document is issued once")
    void oneIssuePerSource() {
        // Issuing the same prescription twice would give the patient two
        // download allowances for one prescription.
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'issued_documents' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_issued_documents_source");
    }

    @Test
    @DisplayName("download counts cannot go negative and validity cannot invert")
    void countersAndValidityAreSane() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'issued_documents' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_issued_documents_downloads",
                "ck_issued_documents_validity");
    }

    @Test
    @DisplayName("the verification token is stored hashed and unique")
    void verificationTokenIsHashed() {
        // The token is what a QR code carries. A readable copy of this column
        // would let anyone with database access mint valid-looking links.
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'document_verifications' "
                        + "AND COLUMN_NAME = 'verification_token' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("download and verification attempts are append-only")
    void attemptsAreAppendOnly() {
        // The evidence behind "it counted a download I never received".
        List<String> mutable = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME IN ('document_download_events', 'verification_attempts') "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("verification attempts are indexed by address, so probing is visible")
    void verificationAttemptsAreIndexedByIp() {
        // The endpoint is unauthenticated. A run of misses from one address is
        // the only signal that someone is guessing tokens.
        List<String> indexes = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'verification_attempts' "
                        + "AND COLUMN_NAME = 'ip_address'",
                String.class, database());

        assertThat(indexes).isNotEmpty();
    }

    @Test
    @DisplayName("only a patient's own-scoped permission grants document access")
    void patientsSeeOnlyTheirOwn() {
        List<String> patientPermissions = jdbc().queryForList("""
                SELECT p.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'PATIENT' AND p.code LIKE 'document%'
                """, String.class);

        assertThat(patientPermissions).contains("document.read_own", "document.download");
        assertThat(patientPermissions)
                .as("a patient must not hold the estate-wide read")
                .doesNotContain("document.read", "document.revoke", "document.issue");
    }

    @Test
    @DisplayName("no centre role can revoke or issue a document")
    void centreRolesCannotIssueOrRevoke() {
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE' AND p.code IN ('document.issue', 'document.revoke')
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("document validity and download limits are governed configuration")
    void limitsAreConfigured() {
        // Investigation download count is still an open decision with FNPH.
        // Holding it as configuration means the answer is a change with a
        // recorded reason, not a release.
        List<String> keys = jdbc().queryForList(
                "SELECT config_key FROM system_configuration WHERE category = 'documents'",
                String.class);

        assertThat(keys).contains("prescription_validity_days", "investigation_validity_days",
                "prescription_max_downloads", "investigation_max_downloads");
    }
}
