package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundary this module has to hold.
 *
 * A free-text ticket thread is the easiest place in the system for clinical
 * information to end up where it should not be. These assertions are what stop
 * a later change turning the helpdesk into a route around the permission
 * matrix.
 */
class HelpdeskSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("no helpdesk table joins to anything clinical")
    void helpdeskCannotReachClinicalData() {
        // Related bookings, payments and documents are referenced by
        // identifier. An agent can see that a booking exists; they cannot
        // follow it into the consultation note or the prescription.
        List<String> joins = jdbc().queryForList("""
                SELECT CONCAT(TABLE_NAME, ' -> ', REFERENCED_TABLE_NAME)
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME IN ('support_tickets', 'ticket_messages', 'ticket_events')
                  AND REFERENCED_TABLE_NAME IN ('consultations', 'consultation_notes',
                        'prescriptions', 'investigations', 'follow_ups',
                        'centre_consultation_notes', 'release_bundles', 'issued_documents',
                        'centre_vitals', 'vitals')
                """, String.class, database());

        assertThat(joins).isEmpty();
    }

    @Test
    @DisplayName("the helpdesk role holds no clinical permission")
    void helpdeskHoldsNoClinicalPermission() {
        List<String> clinical = jdbc().queryForList("""
                SELECT p.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'HELPDESK'
                  AND (p.code LIKE 'clinical_note%' OR p.code LIKE 'prescription%'
                    OR p.code LIKE 'investigation%' OR p.code LIKE 'vitals%'
                    OR p.code LIKE 'consultation%' OR p.code LIKE 'release_bundle%')
                """, String.class);

        assertThat(clinical).isEmpty();
    }

    @Test
    @DisplayName("the helpdesk role holds no financial permission")
    void helpdeskHoldsNoFinancialPermission() {
        // A ticket about a payment is escalated to Finance. The agent can see
        // the reference, not move money.
        List<String> financial = jdbc().queryForList("""
                SELECT p.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'HELPDESK'
                  AND (p.code LIKE 'wallet%' OR p.code LIKE 'payment.refund'
                    OR p.code LIKE 'payment.reconcile')
                """, String.class);

        assertThat(financial).isEmpty();
    }

    @Test
    @DisplayName("ticket messages and events are append-only")
    void threadsAreAppendOnly() {
        // A support conversation that can be edited afterwards is not a record
        // of what was said.
        List<String> mutable = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME IN ('ticket_messages', 'ticket_events') "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("internal notes are distinguishable from correspondence")
    void internalNotesAreMarked() {
        // Without the flag, staff either write nothing down or write it
        // somewhere outside the system.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'ticket_messages' "
                        + "AND COLUMN_NAME = 'is_internal'",
                String.class, database());

        assertThat(columns).isNotEmpty();
    }

    @Test
    @DisplayName("escalation targets are configuration, and the clinical one is governed")
    void escalationTargetsAreConfigured() {
        // Who handles a payment query is operational. Who answers a patient's
        // question about their treatment is clinical, so that one is
        // governance-owned.
        List<String> governed = jdbc().queryForList(
                "SELECT config_key FROM system_configuration "
                        + "WHERE config_key LIKE 'helpdesk_%escalation_role' "
                        + "AND requires_governance = 1",
                String.class);

        assertThat(governed).contains("helpdesk_clinical_escalation_role");

        List<String> all = jdbc().queryForList(
                "SELECT config_key FROM system_configuration "
                        + "WHERE config_key LIKE 'helpdesk_%'",
                String.class);

        assertThat(all).contains("helpdesk_first_response_minutes",
                "helpdesk_clinical_escalation_role",
                "helpdesk_technical_escalation_role",
                "helpdesk_payment_escalation_role");
    }

    @Test
    @DisplayName("a ticket number identifies exactly one ticket")
    void ticketNumberIsUnique() {
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'support_tickets' "
                        + "AND COLUMN_NAME = 'ticket_number' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("first response time is recorded, not assumed")
    void firstResponseIsMeasured() {
        // The target is configuration. What actually happened is a column, so
        // the figure reported to FNPH is measured rather than asserted.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'support_tickets' "
                        + "AND COLUMN_NAME IN ('first_responded_at', 'first_response_minutes')",
                String.class, database());

        assertThat(columns).hasSize(2);
    }
}
