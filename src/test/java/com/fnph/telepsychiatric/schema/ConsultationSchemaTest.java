package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Consultation properties that must not regress. */
class ConsultationSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("no join token is stored in a readable form")
    void tokensAreHashed() {
        // A meeting token is a bearer credential for a live clinical
        // consultation, and there is no second factor inside a video room.
        List<String> plaintext = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'participant_tokens' "
                        + "AND COLUMN_NAME IN ('token', 'provider_token', 'meeting_token', 'jwt')",
                String.class, database());

        assertThat(plaintext).isEmpty();
    }

    @Test
    @DisplayName("a token hash is unique")
    void tokenHashIsUnique() {
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'participant_tokens' "
                        + "AND COLUMN_NAME = 'token_hash' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("a token is bounded at both ends and belongs to one session")
    void tokensHaveAWindow() {
        // Not valid before the join window, dead at the slot end, so a
        // forwarded link is worth nothing before or after.
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'participant_tokens' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_participant_tokens_window",
                "ck_participant_tokens_one_session");
    }

    @Test
    @DisplayName("a repeated provider webhook cannot record the same join twice")
    void attendanceIsIdempotent() {
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'attendance_events' "
                        + "AND COLUMN_NAME = 'provider_event_id' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("attendance and quality events are append-only")
    void eventsAreAppendOnly() {
        // The evidence behind a no-show and a disputed attendance.
        List<String> mutable = jdbc().queryForList(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME IN ('attendance_events', 'connection_quality_events') "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("one room name maps to one consultation")
    void roomNameIsUnique() {
        // Two consultations sharing a room name would put two patients in one
        // call.
        List<String> unique = jdbc().queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND COLUMN_NAME = 'room_name' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).contains("consultations", "centre_consultations");
    }

    @Test
    @DisplayName("recording ships disabled")
    void recordingIsOff() {
        String value = jdbc().queryForObject(
                "SELECT config_value FROM system_configuration WHERE config_key = 'recording_enabled'",
                String.class);

        assertThat(value)
                .as("stays off until FNPH approve consent, retention, access, data location, "
                        + "deletion and incident response")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("a recording carries its consent and retention columns")
    void recordingModelsConsent() {
        // A recording without a recorded consent is evidence of a conversation
        // nobody agreed to record. Building these now means turning recording
        // on later is a governance decision, not a schema change in a hurry.
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'recordings' "
                        + "AND COLUMN_NAME IN ('consent_acceptance_id', 'all_participants_notified', "
                        + "'retention_expires_at', 'deleted_at')",
                String.class, database());

        assertThat(columns).hasSize(4);
    }

    @Test
    @DisplayName("a transcript is a draft until a clinician reviews it")
    void transcriptsAreDraftsUntilReviewed() {
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'transcripts' "
                        + "AND COLUMN_NAME IN ('clinician_reviewed_at', 'clinician_reviewed_by', "
                        + "'retained_clinically')",
                String.class, database());

        assertThat(columns).hasSize(3);
    }

    @Test
    @DisplayName("only the clinician can terminate a session")
    void terminationIsCliniciansAlone() {
        List<String> holders = jdbc().queryForList("""
                SELECT r.code FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'consultation.terminate'
                """, String.class);

        assertThat(holders).contains("DOCTOR");
        assertThat(holders)
                .as("a patient who could end the session could end it for the doctor")
                .doesNotContain("PATIENT", "CENTRE_HUB_COORDINATOR");
    }
}
