package com.fnph.telepsychiatric.acceptance;

import com.fnph.telepsychiatric.schema.AbstractMigratedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACCEPTANCE GATE — duplicate Remita callbacks produce one payment state.
 *
 * Remita retries. A dropped response, a slow reply or a network blip all
 * produce a second callback for the same transaction, and they can arrive
 * simultaneously rather than in sequence.
 *
 * Written to contend for the same reason as the slot test: delivering the same
 * callback twice in a row passes on a system with no idempotency at all if the
 * first has already committed. Simultaneous delivery is the case that finds a
 * missing unique index.
 */
class DuplicateWebhookAcceptanceTest extends AbstractMigratedDatabaseTest {

    private static final int DELIVERIES = 10;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("GATE: ten simultaneous deliveries of one callback store exactly one")
    void duplicateCallbacksCollapseToOne() throws Exception {
        String payload = "{\"orderId\":\"FNPH-GATE-0001\",\"status\":\"00\",\"amount\":\"10000\"}";
        String payloadHash = sha256(payload);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(DELIVERIES);
        AtomicInteger stored = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(DELIVERIES);

        for (int i = 0; i < DELIVERIES; i++) {
            final int index = i;
            pool.submit(() -> {
                try (Connection connection = DATA_SOURCE.getConnection()) {
                    start.await();
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO webhook_inbox
                              (created_at, public_id, created_by, provider, provider_event_id,
                               payload, payload_hash, signature_valid, received_at,
                               processing_state, attempts)
                            VALUES (UTC_TIMESTAMP(6), ?, 'test', 'REMITA', 'FNPH-GATE-0001',
                                    ?, ?, b'1', UTC_TIMESTAMP(6), 'RECEIVED', 0)
                            """)) {
                        insert.setString(1, "01WHK" + String.format("%021d", index));
                        insert.setString(2, payload);
                        insert.setString(3, payloadHash);
                        insert.executeUpdate();
                        stored.incrementAndGet();
                    }
                } catch (Exception e) {
                    // The unique index on payload_hash doing its job.
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(stored.get())
                .as("exactly one of %d simultaneous deliveries is stored", DELIVERIES)
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(DELIVERIES - 1);

        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM webhook_inbox WHERE payload_hash = ?",
                Integer.class, payloadHash);
        assertThat(rows)
                .as("one callback, one row, therefore one payment state")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GATE: a different callback for the same order is still stored")
    void distinctCallbacksAreNotSuppressed() {
        // The idempotency key is the payload, not the order. A later status
        // change for the same transaction is a real event and must not be
        // swallowed as a duplicate.
        String first = "{\"orderId\":\"FNPH-GATE-0002\",\"status\":\"021\"}";
        String second = "{\"orderId\":\"FNPH-GATE-0002\",\"status\":\"00\"}";

        insert("01WHKA0000000000000000001", first);
        insert("01WHKA0000000000000000002", second);

        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM webhook_inbox WHERE provider_event_id = 'FNPH-GATE-0002'",
                Integer.class);
        assertThat(rows).isEqualTo(2);
    }

    private void insert(String publicId, String payload) {
        jdbc().update("""
                INSERT INTO webhook_inbox
                  (created_at, public_id, created_by, provider, provider_event_id, payload,
                   payload_hash, signature_valid, received_at, processing_state, attempts)
                VALUES (UTC_TIMESTAMP(6), ?, 'test', 'REMITA', 'FNPH-GATE-0002', ?, ?, b'1',
                        UTC_TIMESTAMP(6), 'RECEIVED', 0)
                """, publicId, payload, sha256(payload));
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
