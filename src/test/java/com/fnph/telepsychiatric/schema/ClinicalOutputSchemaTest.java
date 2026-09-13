package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundaries the clinical output chain depends on.
 *
 * Each of these is a stated rule from the specification that a later change
 * could remove without anyone noticing what it was protecting.
 */
class ClinicalOutputSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("pharmacy and laboratory cannot read the clinical note")
    void reviewersCannotReadTheNote() {
        // The specification is explicit: pharmacy and laboratory see patient
        // identity, permitted biodata, vitals and submitted material, never the
        // doctor's detailed clinical note.
        List<String> holders = jdbc().queryForList("""
                SELECT r.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'clinical_note.read'
                """, String.class);

        assertThat(holders).doesNotContain("PHARMACIST", "LABORATORY_TECHNICIAN",
                "CENTRE_PHARMACY", "CENTRE_LABORATORY");
    }

    @Test
    @DisplayName("only the doctor authors clinical content")
    void authorshipIsTheDoctorsAlone() {
        List<String> writers = jdbc().queryForList("""
                SELECT DISTINCT r.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code IN ('clinical_note.write', 'clinical_note.sign',
                                 'prescription.write', 'investigation.write')
                """, String.class);

        assertThat(writers).containsOnly("DOCTOR");
    }

    @Test
    @DisplayName("the Hub Coordinator releases but does not author")
    void coordinatorChecksRatherThanWrites() {
        // The release check is administrative. The person who confirms the
        // paperwork is done is not the person who decides the treatment.
        List<String> permissions = jdbc().queryForList("""
                SELECT p.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'HUB_COORDINATOR'
                """, String.class);

        assertThat(permissions).contains("release_bundle.release", "release_bundle.read");
        assertThat(permissions).doesNotContain("clinical_note.write", "clinical_note.sign",
                "prescription.write", "investigation.write");
    }

    @Test
    @DisplayName("a review is of exactly one document")
    void reviewHasOneSubject() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'professional_reviews' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_professional_reviews_one_subject");
    }

    @Test
    @DisplayName("a document is reviewed once per review type")
    void oneReviewPerDocumentPerType() {
        // Two pharmacy reviews of one prescription would give the coordinator
        // two answers and no way to tell which is current.
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'professional_reviews' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_professional_reviews_prescription",
                "uk_professional_reviews_investigation");
    }

    @Test
    @DisplayName("nothing in the review model points back at the doctor")
    void reviewsHaveNoReturnPath() {
        // The one-way rule, checked structurally. A column named for returning
        // to the author is the shape a return path would take.
        List<String> returnColumns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'professional_reviews' "
                        + "AND (COLUMN_NAME LIKE '%returned%' OR COLUMN_NAME LIKE '%sent_back%' "
                        + "  OR COLUMN_NAME LIKE '%reopen%' OR COLUMN_NAME LIKE '%to_doctor%')",
                String.class, database());

        assertThat(returnColumns)
                .as("a correction is a new document the doctor authors, never an edit")
                .isEmpty();
    }

    @Test
    @DisplayName("one bundle per appointment")
    void oneBundlePerAppointment() {
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'release_bundles' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_release_bundles_appointment",
                "uk_release_bundles_centre_appointment");
    }

    @Test
    @DisplayName("a bundle belongs to exactly one pathway")
    void bundleHasOnePathway() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'release_bundles' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_release_bundles_one_pathway");
    }

    @Test
    @DisplayName("a bundle lists each component type once")
    void oneComponentRowPerType() {
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'release_bundle_components' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_release_bundle_components_type");
    }

    @Test
    @DisplayName("clinical notes are versioned, so an amendment supersedes rather than edits")
    void notesAreVersioned() {
        // Editing a signed note in place destroys exactly the evidence an
        // investigation would want: what was written first.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'consultation_notes' "
                        + "AND COLUMN_NAME IN ('version', 'supersedes_id', 'superseded_at', "
                        + "'amendment_reason')",
                String.class, database());

        assertThat(columns).hasSize(4);
    }

    @Test
    @DisplayName("a component can be marked not required with a reason")
    void notRequiredIsAState() {
        // Without it, a consultation that legitimately produced no
        // investigation request sits blocked forever waiting for a document
        // nobody intends to write.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'release_bundle_components' "
                        + "AND COLUMN_NAME IN ('not_required', 'not_required_reason')",
                String.class, database());

        assertThat(columns).hasSize(2);
    }
}
