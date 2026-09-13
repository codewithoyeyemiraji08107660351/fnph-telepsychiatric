package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraints that make double booking impossible.
 *
 * Slot concurrency is an acceptance gate. Service-layer locking handles the
 * common case; these are what hold if every check above them were bypassed.
 */
class SchedulingSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("a slot can carry only one appointment")
    void oneAppointmentPerSlot() {
        // The last line of defence. Two patients cannot both hold the same
        // time even if the service-layer lock were removed.
        List<String> unique = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'appointments' "
                        + "AND COLUMN_NAME = 'slot_id' AND NON_UNIQUE = 0",
                String.class, database());

        assertThat(unique).isNotEmpty();
    }

    @Test
    @DisplayName("slots carry a version column for optimistic locking")
    void slotsAreVersioned() {
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'slots' AND COLUMN_NAME = 'version'",
                String.class, database());

        assertThat(columns).isNotEmpty();
    }

    @Test
    @DisplayName("one publication per audience per day")
    void onePublicationPerAudiencePerDay() {
        // Two publications for one day would generate two overlapping grids and
        // the patient would see the same time twice.
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'schedule_publications' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_schedule_publications_audience_date");
    }

    @Test
    @DisplayName("a slot or a publication cannot end before it starts")
    void windowsAreSane() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_TYPE = 'CHECK' "
                        + "AND TABLE_NAME IN ('slots', 'schedule_publications', 'doctor_availability')",
                String.class, database());

        assertThat(checks).contains("ck_slots_window", "ck_schedule_window",
                "ck_doctor_availability_window");
    }

    @Test
    @DisplayName("slot holds are indexed by expiry so the sweep is not a scan")
    void holdsAreIndexedByExpiry() {
        // Swept continuously. An unindexed sweep scans a table that only grows.
        List<String> indexes = jdbc().queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'slot_holds' "
                        + "AND COLUMN_NAME = 'expires_at'",
                String.class, database());

        assertThat(indexes).isNotEmpty();
    }

    @Test
    @DisplayName("appointment status history is append-only")
    void statusHistoryIsAppendOnly() {
        // Reconstructing who approved what and when is an acceptance
        // requirement. An editable history cannot answer it.
        List<String> mutable = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'appointment_status_history' "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("rooms are seeded, including a contingency room")
    void roomsAreSeeded() {
        List<String> codes = jdbc().queryForList(
                "SELECT code FROM rooms WHERE is_active = 1", String.class);

        assertThat(codes).contains("ROOM-01", "ROOM-CT");
    }

    @Test
    @DisplayName("no centre role can publish a schedule or approve a booking")
    void centreRolesCannotSchedule() {
        // Centres select from a schedule the FNPH Hub Coordinator configures.
        // They do not publish one, and they do not approve their own bookings.
        List<String> crossings = jdbc().queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('schedule.publish', 'appointment.approve', 'appointment.reject',
                                 'appointment.assign_doctor', 'appointment.assign_team',
                                 'appointment.assign_room', 'room.manage',
                                 'doctor_availability.manage')
                """, String.class);

        assertThat(crossings).isEmpty();
    }
}
