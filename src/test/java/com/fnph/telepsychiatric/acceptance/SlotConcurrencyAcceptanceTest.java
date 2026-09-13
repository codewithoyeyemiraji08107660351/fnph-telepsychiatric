package com.fnph.telepsychiatric.acceptance;

import com.fnph.telepsychiatric.schema.AbstractMigratedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACCEPTANCE GATE — slot concurrency.
 *
 * "Two patients cannot hold the same slot." This is the test FNPH will ask to
 * see run, and it is written to actually contend rather than to demonstrate.
 *
 * Real threads, real connections, a real MySQL container, and a start barrier
 * so every thread reaches the same slot at the same moment. A sequential test
 * that books twice in a row passes on a system with no locking at all, which
 * is why it is not written that way.
 *
 * Two layers are exercised:
 *   1. The pessimistic claim the service uses (SELECT ... FOR UPDATE).
 *   2. The unique index on appointments.slot_id, which holds even if the
 *      service-layer lock were removed.
 */
class SlotConcurrencyAcceptanceTest extends AbstractMigratedDatabaseTest {

    private static final int CONTENDERS = 20;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("GATE: twenty simultaneous attempts on one slot produce exactly one booking")
    void onlyOnePatientCanTakeASlot() throws Exception {
        long slotId = seedOneAvailableSlot();
        List<Long> patients = seedPatients(CONTENDERS);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);

        for (int i = 0; i < CONTENDERS; i++) {
            final long patientId = patients.get(i);
            final int index = i;
            pool.submit(() -> {
                try (Connection connection = DATA_SOURCE.getConnection()) {
                    connection.setAutoCommit(false);
                    // Every thread waits here, then goes at once. Without the
                    // barrier the threads arrive in sequence and the test
                    // proves nothing.
                    start.await();

                    // The claim the service performs: lock the row, check the
                    // state, then write.
                    try (var lock = connection.prepareStatement(
                            "SELECT state FROM slots WHERE id = ? FOR UPDATE")) {
                        lock.setLong(1, slotId);
                        try (var rs = lock.executeQuery()) {
                            if (!rs.next() || !"AVAILABLE".equals(rs.getString(1))) {
                                connection.rollback();
                                refused.incrementAndGet();
                                return null;
                            }
                        }
                    }

                    try (var claim = connection.prepareStatement(
                            "UPDATE slots SET state = 'HELD', version = version + 1 "
                                    + "WHERE id = ? AND state = 'AVAILABLE'")) {
                        claim.setLong(1, slotId);
                        if (claim.executeUpdate() == 0) {
                            connection.rollback();
                            refused.incrementAndGet();
                            return null;
                        }
                    }

                    try (var book = connection.prepareStatement("""
                            INSERT INTO appointments
                              (created_at, public_id, created_by, patient_id, slot_id, reference,
                               appointment_date, duration_minutes, status, version)
                            VALUES (UTC_TIMESTAMP(6), ?, 'test', ?, ?, ?,
                                    '2026-11-02 09:00:00', 30, 'SLOT_HELD', 0)
                            """)) {
                        book.setString(1, "01CONC" + String.format("%020d", index));
                        book.setLong(2, patientId);
                        book.setLong(3, slotId);
                        book.setString(4, "APT-CONC-" + index);
                        book.executeUpdate();
                    }

                    connection.commit();
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    // A unique-key violation here is the second layer doing its
                    // job, and it is a refusal, not a failure.
                    refused.incrementAndGet();
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS))
                .as("all contenders finished").isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get())
                .as("exactly one of %d simultaneous attempts may take the slot", CONTENDERS)
                .isEqualTo(1);
        assertThat(refused.get()).isEqualTo(CONTENDERS - 1);

        Integer bookings = jdbc().queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE slot_id = ?", Integer.class, slotId);
        assertThat(bookings)
                .as("the database holds one appointment for that slot")
                .isEqualTo(1);

        String state = jdbc().queryForObject(
                "SELECT state FROM slots WHERE id = ?", String.class, slotId);
        assertThat(state).isEqualTo("HELD");
    }

    @Test
    @DisplayName("GATE: the unique index alone stops a double booking")
    void uniqueIndexHoldsWithoutTheServiceLock() {
        // Deliberately bypasses the locking above. This is what protects the
        // system if a future change drops the SELECT ... FOR UPDATE, which is
        // the realistic way this regresses.
        long slotId = seedOneAvailableSlot();
        List<Long> patients = seedPatients(2);

        jdbc().update("""
                INSERT INTO appointments
                  (created_at, public_id, created_by, patient_id, slot_id, reference,
                   appointment_date, duration_minutes, status, version)
                VALUES (UTC_TIMESTAMP(6), ?, 'test', ?, ?, ?, '2026-11-02 09:00:00', 30,
                        'SLOT_HELD', 0)
                """, "01UNIQ" + String.format("%020d", 1), patients.get(0), slotId, "APT-UNIQ-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO appointments
                  (created_at, public_id, created_by, patient_id, slot_id, reference,
                   appointment_date, duration_minutes, status, version)
                VALUES (UTC_TIMESTAMP(6), ?, 'test', ?, ?, ?, '2026-11-02 09:00:00', 30,
                        'SLOT_HELD', 0)
                """, "01UNIQ" + String.format("%020d", 2), patients.get(1), slotId, "APT-UNIQ-2"))
                .as("a second appointment on the same slot must be impossible")
                .isInstanceOf(Exception.class);
    }

    // -----------------------------------------------------------------

    private long seedOneAvailableSlot() {
        String suffix = String.format("%08d", (int) (Math.random() * 99_999_999));
        jdbc().update("""
                INSERT INTO schedule_publications
                  (created_at, public_id, created_by, audience, service_date, window_start,
                   window_end, slot_minutes, status, slots_generated)
                VALUES (UTC_TIMESTAMP(6), ?, 'test', 'FNPH_PATIENT', ?, '09:00:00', '12:00:00',
                        30, 'PUBLISHED', 1)
                """, "01PUB" + suffix + "0000000000000", "2026-11-0"
                + (1 + (int) (Math.random() * 8)));

        Long publicationId = jdbc().queryForObject(
                "SELECT id FROM schedule_publications ORDER BY id DESC LIMIT 1", Long.class);

        jdbc().update("""
                INSERT INTO slots
                  (created_at, public_id, created_by, publication_id, start_at, end_at,
                   state, version)
                VALUES (UTC_TIMESTAMP(6), ?, 'test', ?, '2026-11-02 09:00:00',
                        '2026-11-02 09:30:00', 'AVAILABLE', 0)
                """, "01SLT" + suffix + "0000000000000", publicationId);

        return jdbc().queryForObject("SELECT id FROM slots ORDER BY id DESC LIMIT 1", Long.class);
    }

    private List<Long> seedPatients(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            String suffix = String.format("%08d", (int) (Math.random() * 99_999_999)) + i;
            jdbc().update("""
                    INSERT INTO patients
                      (created_at, public_id, created_by, ehr_number, first_name, last_name,
                       date_of_birth, is_eligible, is_physically_assessed, is_active,
                       drift_flagged, deleted)
                    VALUES (UTC_TIMESTAMP(6), ?, 'test', ?, 'Test', 'Patient', '1990-01-01',
                            b'1', b'1', b'1', b'0', b'0')
                    """, ("01PAT" + suffix + "000000000000000").substring(0, 26),
                    "FNPH/CONC/" + suffix);
            return jdbc().queryForObject(
                    "SELECT id FROM patients ORDER BY id DESC LIMIT 1", Long.class);
        }).toList();
    }
}
