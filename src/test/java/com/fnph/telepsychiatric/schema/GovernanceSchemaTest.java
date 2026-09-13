package com.fnph.telepsychiatric.schema;

import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Governance data that must not silently drift. */
class GovernanceSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("every ConfigurationKeys constant exists in the database")
    void everyConstantIsSeeded() {
        // A typo in a key string returns nothing and, without this, would be
        // discovered when a fee or a session length came out wrong.
        List<String> seeded = jdbc().queryForList(
                "SELECT config_key FROM system_configuration", String.class);

        List<String> missing = Arrays.stream(ConfigurationKeys.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(f -> {
                    try {
                        f.setAccessible(true);
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(key -> !seeded.contains(key))
                .sorted()
                .toList();

        assertThat(missing).as("constants with no seeded key").isEmpty();
    }

    @Test
    @DisplayName("the three session timing values are separate keys")
    void timingValuesAreNotConflated() {
        // The source documents give three different numbers for what reads
        // like one setting. Merging them here would reproduce that ambiguity
        // in code, where it is much harder to see.
        List<String> keys = jdbc().queryForList(
                "SELECT config_key FROM system_configuration WHERE category = 'consultation'",
                String.class);

        assertThat(keys).contains(
                ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES,
                ConfigurationKeys.JOINING_GRACE_MINUTES,
                ConfigurationKeys.NO_SHOW_CUTOFF_MINUTES);
    }

    @Test
    @DisplayName("session length is per audience, not global")
    void sessionLengthIsPerAudience() {
        // A single global key would let a Centre duration change silently move
        // FNPH appointments.
        List<String> keys = jdbc().queryForList(
                "SELECT config_key FROM system_configuration WHERE config_key LIKE 'session_minutes%'",
                String.class);

        assertThat(keys).containsExactlyInAnyOrder(
                ConfigurationKeys.SESSION_MINUTES_FNPH,
                ConfigurationKeys.SESSION_MINUTES_CENTRE);
    }

    @Test
    @DisplayName("recording ships disabled")
    void recordingIsOff() {
        // Stays off until FNPH approves consent, retention, access, data
        // location, deletion and incident response.
        String value = jdbc().queryForObject(
                "SELECT config_value FROM system_configuration WHERE config_key = ?",
                String.class, ConfigurationKeys.RECORDING_ENABLED);

        assertThat(value).isEqualTo("false");
    }

    @Test
    @DisplayName("every setting has a history row, so history starts at creation")
    void everySettingHasHistory() {
        Integer orphans = jdbc().queryForObject("""
                SELECT COUNT(*) FROM system_configuration c
                WHERE NOT EXISTS (
                    SELECT 1 FROM configuration_changes h WHERE h.config_key = c.config_key)
                """, Integer.class);

        assertThat(orphans)
                .as("without a seeded initial row, the first edit would show no previous value")
                .isZero();
    }

    @Test
    @DisplayName("no state-changing permission is available during supervised access")
    void supervisionIsReadOnly() {
        // Supervised access grants the target's non-mutating permissions only.
        // A write permission marked non-mutating would let an administrator
        // write in a clinician's name, and the result would be
        // indistinguishable from one the clinician wrote.
        List<String> wronglyReadOnly = jdbc().queryForList("""
                SELECT code FROM permissions
                WHERE is_mutating = 0
                  AND (code LIKE '%.write' OR code LIKE '%.sign' OR code LIKE '%.approve'
                    OR code LIKE '%.release' OR code LIKE '%.assign' OR code LIKE '%.create'
                    OR code LIKE '%.update' OR code LIKE '%.delete' OR code LIKE '%.credit'
                    OR code LIKE '%.refund' OR code LIKE '%.download' OR code LIKE '%.revoke'
                    OR code LIKE '%.terminate' OR code LIKE '%.join_%')
                """, String.class);

        assertThat(wronglyReadOnly)
                .as("these change state and must not be available during supervision")
                .isEmpty();
    }

    @Test
    @DisplayName("audit chain and supervision columns exist")
    void auditColumnsExist() {
        List<String> columns = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'audit_logs' "
                        + "AND COLUMN_NAME IN ('chain_hash', 'previous_chain_hash', "
                        + "'effective_principal_id', 'view_as_session_id', 'reason')",
                String.class, database());

        assertThat(columns).hasSize(5);
    }

    @Test
    @DisplayName("an administrator cannot supervise their own account")
    void selfSupervisionIsBlockedByTheDatabase() {
        List<String> checks = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'view_as_sessions' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                String.class, database());

        assertThat(checks).contains("ck_view_as_not_self");
    }

    @Test
    @DisplayName("configuration_changes is append-only")
    void configurationHistoryIsAppendOnly() {
        List<String> mutable = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'configuration_changes' "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }
}
