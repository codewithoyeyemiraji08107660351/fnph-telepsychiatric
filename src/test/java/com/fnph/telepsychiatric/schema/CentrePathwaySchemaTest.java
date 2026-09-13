package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The separations the centre pathway depends on.
 *
 * Every one of these is a stated rule that a later change could remove without
 * anyone noticing what it was protecting.
 */
class CentrePathwaySchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("referral clinical context no longer lives on the patient row")
    void referralContextMovedOffThePatient() {
        // A patient seen three times has three presenting conditions. On the
        // patient row, each new referral silently overwrote the last.
        List<String> leftover = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'centre_patients' "
                        + "AND COLUMN_NAME IN ('referral_reason', 'assessment', "
                        + "'current_condition', 'relevant_medicines')",
                String.class, database());

        assertThat(leftover).isEmpty();
    }

    @Test
    @DisplayName("consent is recorded per referral, not per patient")
    void consentIsPerReferral() {
        // A patient consented to a consultation in March. That is not consent
        // to one in September.
        List<String> onReferral = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'centre_referrals' "
                        + "AND COLUMN_NAME IN ('consent_version', 'consent_accepted_at', "
                        + "'consent_accepted_by')",
                String.class, database());

        assertThat(onReferral).hasSize(3);

        List<String> onPatient = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'centre_patients' "
                        + "AND COLUMN_NAME LIKE 'consent%'",
                String.class, database());

        assertThat(onPatient).isEmpty();
    }

    @Test
    @DisplayName("a centre appointment holds at most one slot")
    void oneCentreAppointmentPerSlot() {
        // The same guarantee the FNPH pathway has. Both draw on the same slot
        // table, so both need it.
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'centre_appointments' "
                        + "AND COLUMN_NAME = 'slot_id' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("an approved booking links to the ledger entry that paid for it")
    void bookingLinksToItsDebit() {
        // "Was this booking charged" has to be answerable from the booking.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'centre_appointments' "
                        + "AND COLUMN_NAME IN ('wallet_transaction_id', 'wallet_debited_at')",
                String.class, database());

        assertThat(columns).hasSize(2);
    }

    @Test
    @DisplayName("a bundle reaches a centre once")
    void oneReceiptPerBundle() {
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'centre_bundle_receipts' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_centre_bundle_receipts_bundle");
    }

    @Test
    @DisplayName("no centre role can see a wallet balance or the ledger")
    void centresNeverSeeAmounts() {
        // Finance credits the wallet from programme funding and holds that
        // view. Centres see consultation and utilisation counts.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('wallet.read_balance', 'wallet.read_ledger', 'wallet.credit')
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("no centre role can reach an FNPH patient record")
    void centresCannotReachFnphPatients() {
        // A centre patient is never an FNPH patient, and the offline FNPH
        // record is never linked or retrieved for one.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('patient.read', 'patient.read_own', 'ehr_import.read',
                                 'ehr_verification.resolve')
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("no centre role can approve its own booking")
    void centresCannotApproveThemselves() {
        // Approval commits an FNPH doctor, room and team, and debits the
        // wallet. It belongs to the FNPH Hub Coordinator.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('appointment.approve', 'appointment.reject',
                                 'release_bundle.release', 'schedule.publish')
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("every centre has a wallet, seeded at zero")
    void everyCentreHasAWallet() {
        Integer without = jdbc().queryForObject("""
                SELECT COUNT(*) FROM centres c
                WHERE NOT EXISTS (SELECT 1 FROM wallets w WHERE w.centre_id = c.id)
                """, Integer.class);

        assertThat(without).isZero();
    }

    @Test
    @DisplayName("wallet alerts and the ledger are indexed for the queries that run constantly")
    void alertLookupsAreIndexed() {
        List<String> indexes = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'wallet_alerts' "
                        + "AND COLUMN_NAME = 'centre_id'",
                String.class, database());

        assertThat(indexes).isNotEmpty();
    }
}
