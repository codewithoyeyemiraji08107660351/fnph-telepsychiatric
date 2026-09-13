package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraints that carry the payment guarantees.
 *
 * Each is the mechanism behind a stated acceptance criterion, and each could be
 * removed by a later migration written by someone who did not know that.
 */
class PaymentSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("a repeat callback cannot be stored twice")
    void webhookPayloadIsUnique() {
        // The mechanism behind "duplicate Remita callbacks produce one payment
        // state". Without this index the second delivery unlocks booking again.
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'webhook_inbox' "
                        + "AND COLUMN_NAME = 'payload_hash' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("an RRR belongs to exactly one payment")
    void rrrIsUnique() {
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'payments' "
                        + "AND COLUMN_NAME = 'rrr' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("the credit ledger is append-only")
    void creditLedgerIsAppendOnly() {
        // Balance is derived from these rows. An editable ledger is a balance
        // that can be changed without a trace.
        List<String> mutable = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'patient_credits' "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("a credit entry must be a positive amount")
    void creditAmountIsPositive() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'patient_credits' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_patient_credits_amount");
    }

    @Test
    @DisplayName("a notification has exactly one addressee")
    void notificationHasOneAddressee() {
        // Neither would be a notification nobody sees. Both would be one
        // delivered twice, and a dashboard count that never reaches zero.
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'notifications' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_notifications_addressee");
    }

    @Test
    @DisplayName("money columns are decimal, never floating point")
    void moneyIsDecimal() {
        List<String> offenders = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND DATA_TYPE IN ('float', 'double') "
                        + "AND TABLE_NAME IN ('payments', 'patient_credits', 'wallets', "
                        + "'wallet_transactions', 'reconciliation_exceptions')",
                String.class, database());

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the outbox can find unpublished events without a scan")
    void outboxIsIndexedForPolling() {
        // Polled continuously. An unindexed poll is a full table scan every few
        // seconds against a table that only ever grows.
        List<String> indexes = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'outbox_events' "
                        + "AND COLUMN_NAME = 'published_at'",
                String.class, database());

        assertThat(indexes).isNotEmpty();
    }

    @Test
    @DisplayName("no centre role can reach patient payments or wallet amounts")
    void centreRolesCannotReachMoney() {
        // Centres do not make patient payments and do not see wallet amounts.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('payment.read', 'payment.initiate', 'payment.reconcile',
                                 'payment.refund', 'wallet.read_balance', 'wallet.read_ledger',
                                 'wallet.credit')
                """, String.class);

        assertThat(crossings).isEmpty();
    }

    @Test
    @DisplayName("no permission exists that activates an ordinary payment")
    void thereIsNoManualActivation() {
        // Finance monitors and reconciles. The strongest way to hold "no manual
        // activation of a normal successful payment" is for the capability not
        // to exist at all.
        List<String> suspicious = jdbc().queryForList(
                "SELECT code FROM permissions WHERE code LIKE 'payment.activate%' "
                        + "OR code LIKE 'payment.force%' OR code LIKE 'payment.mark_%'",
                String.class);

        assertThat(suspicious).isEmpty();
    }
}
