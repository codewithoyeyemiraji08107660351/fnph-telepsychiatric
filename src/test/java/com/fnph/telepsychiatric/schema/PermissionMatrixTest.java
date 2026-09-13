package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the seeded permission matrix against the specification.
 *
 * Each case below traces to a stated boundary in the FNPH documents. These are
 * the assertions that make a wrong grant fail the build rather than being
 * discovered when a pharmacist opens a clinical note in production.
 */
class PermissionMatrixTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    private boolean granted(String roleCode, String permissionCode) {
        Integer count = jdbc().queryForObject("""
                SELECT COUNT(*) FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = ? AND p.code = ?
                """, Integer.class, roleCode, permissionCode);
        return count != null && count > 0;
    }

    // -----------------------------------------------------------------
    // Integrity of the matrix itself
    // -----------------------------------------------------------------

    @Test
    @DisplayName("all sixteen roles are seeded, including the two the documents never defined")
    void allRolesSeeded() {
        List<String> codes = jdbc().queryForList(
                "SELECT code FROM roles ORDER BY code", String.class);

        assertThat(codes).hasSize(16);
        assertThat(codes).contains(
                "CENTRAL_ADMINISTRATOR", "HUB_COORDINATOR", "DOCTOR", "PHARMACIST",
                "LABORATORY_TECHNICIAN", "NURSING", "HIM", "FINANCE", "PATIENT",
                "CENTRE_HUB_COORDINATOR", "CENTRE_ASSISTANT_COORDINATOR",
                "CENTRE_PHARMACY", "CENTRE_LABORATORY", "CENTRE_HIM");

        // ICT appears in the unmatched-EHR verification path and HELPDESK owns a
        // queue in the administrator prototype. Neither existed in the role
        // table. Both are created here with deliberately narrow scope.
        assertThat(codes).contains("ICT_SUPPORT", "HELPDESK");
    }

    @Test
    @DisplayName("no role is left with zero permissions")
    void noEmptyRoles() {
        List<String> empty = jdbc().queryForList("""
                SELECT r.code FROM roles r
                WHERE NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id)
                """, String.class);

        assertThat(empty)
                .as("a role granting nothing lets a user authenticate and then do nothing, "
                        + "which is confusing to diagnose")
                .isEmpty();
    }

    @Test
    @DisplayName("no permission is left ungranted to every role")
    void noOrphanPermissions() {
        List<String> orphans = jdbc().queryForList("""
                SELECT p.code FROM permissions p
                WHERE NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.permission_id = p.id)
                """, String.class);

        assertThat(orphans)
                .as("an ungranted permission is either dead weight or an omission in the matrix")
                .isEmpty();
    }

    @Test
    @DisplayName("every role has a dashboard route, so nobody sees a role selector")
    void everyRoleHasADashboard() {
        List<String> missing = jdbc().queryForList(
                "SELECT code FROM roles WHERE dashboard_route IS NULL OR dashboard_route = ''",
                String.class);

        assertThat(missing).isEmpty();
    }

    // -----------------------------------------------------------------
    // Boundaries stated in the specification
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "{0} must NOT hold {1} — {2}")
    @CsvSource(delimiter = '|', value = {
        "PHARMACIST|clinical_note.read|pharmacy sees identity, biodata, vitals and submitted material, never the doctor's clinical note",
        "LABORATORY_TECHNICIAN|clinical_note.read|laboratory has the same boundary as pharmacy",
        "NURSING|clinical_note.read|nursing enters vitals and assigns a room; the workflow ends at preparation complete",
        "HIM|clinical_note.read|HIM retrieves the offline record and marks the task treated",
        "HIM|appointment.approve|HIM has no approval or rejection authority",
        "HIM|appointment.reject|HIM has no approval or rejection authority",
        "NURSING|appointment.approve|nursing has no approval authority",
        "CENTRE_HUB_COORDINATOR|wallet.read_balance|centres see consultation and utilisation counts, never wallet amounts",
        "CENTRE_ASSISTANT_COORDINATOR|wallet.read_balance|same boundary as the centre coordinator",
        "CENTRE_HUB_COORDINATOR|wallet.read_ledger|the ledger is Finance only",
        "CENTRE_HUB_COORDINATOR|patient.read|a centre never reaches an FNPH patient record",
        "CENTRE_HUB_COORDINATOR|centre.read|a centre sees only its own centre",
        "CENTRAL_ADMINISTRATOR|clinical_note.write|supervising a clinician is not writing in their name",
        "CENTRAL_ADMINISTRATOR|clinical_note.sign|clinician-authored content is not altered by anyone else",
        "CENTRAL_ADMINISTRATOR|prescription.write|clinical authorship is the doctor's alone",
        "CENTRAL_ADMINISTRATOR|review.pharmacy|professional verification is the pharmacist's alone",
        "HUB_COORDINATOR|clinical_note.write|the Hub Coordinator performs a completeness and release check, not authorship",
        "HELPDESK|clinical_note.read|helpdesk handles tickets and has no clinical access",
        "HELPDESK|prescription.read|helpdesk has no clinical access",
        "ICT_SUPPORT|clinical_note.read|ICT is technical scope only",
        "ICT_SUPPORT|patient.read|ICT resolves verification exceptions without reading clinical records",
        "PATIENT|patient.read|a patient reads their own record only, via patient.read_own",
        "PATIENT|appointment.read|a patient sees their own appointments only",
        "PATIENT|appointment.approve|approval is the Hub Coordinator's",
        "PHARMACIST|prescription.write|pharmacy verifies; it does not author",
        "LABORATORY_TECHNICIAN|investigation.write|laboratory reviews; it does not author",
        "FINANCE|clinical_note.read|finance has no clinical access",
        "DOCTOR|appointment.approve|approval and assignment belong to the Hub Coordinator",
        "DOCTOR|release_bundle.release|release is an administrative completeness check",
    })
    void forbiddenGrants(String role, String permission, String rationale) {
        assertThat(granted(role, permission))
                .as("%s must not hold %s: %s", role, permission, rationale)
                .isFalse();
    }

    @ParameterizedTest(name = "{0} must hold {1} — {2}")
    @CsvSource(delimiter = '|', value = {
        "CENTRAL_ADMINISTRATOR|supervision.view_as|only the Central Administrator has supervised access across dashboards",
        "CENTRAL_ADMINISTRATOR|audit.read|the administrator reviews the audit trail",
        "CENTRAL_ADMINISTRATOR|config.update|configuration changes are the administrator's, with a recorded reason",
        "CENTRAL_ADMINISTRATOR|role.assign|the administrator owns the permission matrix",
        "CENTRAL_ADMINISTRATOR|centre_capability.manage|optional local centre roles are activated by the administrator",
        "HUB_COORDINATOR|appointment.approve|the Hub Coordinator approves bookings",
        "HUB_COORDINATOR|appointment.assign_team|and assigns room, doctor and team",
        "HUB_COORDINATOR|release_bundle.release|and performs the completeness and release check",
        "HUB_COORDINATOR|schedule.publish|slots are configured by the Hub Coordinator",
        "DOCTOR|clinical_note.write|the doctor authors the clinical note",
        "DOCTOR|prescription.write|and the prescription",
        "DOCTOR|consultation.terminate|the clinician may end a session and record the reason and safety action",
        "DOCTOR|consultation.switch_modality|video is standard, audio is the approved fallback",
        "PHARMACIST|review.pharmacy|pharmacy transcribes and professionally verifies",
        "PHARMACIST|review.submit_to_hub|reviews travel forward to the Hub Coordinator",
        "LABORATORY_TECHNICIAN|review.laboratory|laboratory transcribes and reviews",
        "NURSING|vitals.verify|nursing records the supplied vitals in the offline EHR",
        "NURSING|appointment.assign_room|and assigns the matching room",
        "HIM|queue.him|HIM works its own queue",
        "FINANCE|payment.reconcile|finance monitors and reconciles",
        "FINANCE|wallet.credit|finance credits a centre wallet from programme funding",
        "PATIENT|patient.read_own|a patient reads their own record",
        "PATIENT|consultation.join_as_patient|and joins their own consultation",
        "PATIENT|document.download|and downloads their released documents within the limit",
        "CENTRE_HUB_COORDINATOR|centre.read_own|a centre sees its own centre",
        "CENTRE_HUB_COORDINATOR|centre_report.read|and its consultation and utilisation counts",
        "CENTRE_HUB_COORDINATOR|centre_bundle.mark_treated|and marks a released bundle treated",
        "HELPDESK|ticket.escalate|helpdesk owns escalation",
        "ICT_SUPPORT|ehr_verification.resolve|ICT works the unmatched verification queue",
    })
    void requiredGrants(String role, String permission, String rationale) {
        assertThat(granted(role, permission))
                .as("%s must hold %s: %s", role, permission, rationale)
                .isTrue();
    }

    @Test
    @DisplayName("no permission that activates an ordinary successful payment exists at all")
    void financeCannotActivateAnOrdinaryPayment() {
        // The specification says Finance performs no manual activation of a
        // normal successful payment. The strongest way to hold that is for the
        // capability not to exist, so no future grant can create it by accident.
        List<String> suspicious = jdbc().queryForList(
                "SELECT code FROM permissions WHERE code LIKE 'payment.activate%' "
                        + "OR code LIKE 'payment.force%' OR code LIKE 'payment.mark_%'",
                String.class);

        assertThat(suspicious).isEmpty();
    }

    @Test
    @DisplayName("patient permissions are own-scoped only")
    void patientHoldsOnlyOwnScopedReads() {
        List<String> broadReads = jdbc().queryForList("""
                SELECT p.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'PATIENT'
                  AND p.code LIKE '%.read'
                  AND p.code NOT IN ('schedule.read', 'slot.read')
                """, String.class);

        assertThat(broadReads)
                .as("a patient may read schedules and slots to book, and otherwise only "
                        + "their own records through _own permissions")
                .isEmpty();
    }

    @Test
    @DisplayName("centre roles hold no permission that crosses into FNPH records")
    void centreRolesCannotReachFnphRecords() {
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('patient.read', 'patient.update', 'patient.create',
                                 'ehr_import.read', 'ehr_import.upload',
                                 'wallet.read_balance', 'wallet.read_ledger',
                                 'centre.read', 'audit.read', 'config.update',
                                 'clinical_note.write', 'appointment.approve')
                """, String.class);

        assertThat(crossings)
                .as("a centre patient is never an FNPH patient, and a centre never sees "
                        + "another centre or the hospital's own records")
                .isEmpty();
    }

    @Test
    @DisplayName("the single-primary-role constraint is enforced by the database")
    void oneUserHasAtMostOnePrimaryRole() {
        List<String> offenders = jdbc().queryForList("""
                SELECT CAST(user_id AS CHAR) FROM user_role
                WHERE is_primary = 1 GROUP BY user_id HAVING COUNT(*) > 1
                """, String.class);

        assertThat(offenders)
                .as("two primary roles would make the post-login redirect non-deterministic")
                .isEmpty();
    }
}
