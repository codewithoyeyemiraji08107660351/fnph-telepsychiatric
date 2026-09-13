package com.fnph.telepsychiatric.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the authentication schema that must not silently regress.
 *
 * Each of these is a control someone could remove in a later migration without
 * noticing what it was for.
 */
class AuthSchemaTest extends AbstractMigratedDatabaseTest {

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    @Test
    @DisplayName("no table has a column that would hold a token in clear")
    void secretsAreNeverStoredReadable() {
        // Refresh tokens, invitation links, reset links and recovery codes are
        // all compared by hash. A column named for the value rather than its
        // hash means somebody stored the real thing.
        List<String> suspicious = jdbc().queryForList("""
                SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND (COLUMN_NAME IN ('refresh_token', 'token', 'reset_token',
                                       'activation_token', 'recovery_code', 'secret')
                   OR  COLUMN_NAME LIKE '%password%plain%')
                """, String.class, database());

        assertThat(suspicious)
                .as("store a hash, or for TOTP an encrypted value, never the secret itself")
                .isEmpty();
    }

    @Test
    @DisplayName("token columns are unique, so a hash collision cannot authenticate two sessions")
    void tokenHashesAreUnique() {
        List<String> tables = jdbc().queryForList("""
                SELECT DISTINCT TABLE_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ? AND NON_UNIQUE = 0
                  AND COLUMN_NAME IN ('refresh_token_hash', 'token_hash', 'code_hash')
                """, String.class, database());

        assertThat(tables).contains("user_sessions", "account_tokens", "mfa_recovery_codes");
    }

    @Test
    @DisplayName("login_attempts is append-only")
    void loginAttemptsIsAppendOnly() {
        // It is the evidence behind lockout and rate limiting. A row that can
        // be edited is evidence that can be edited.
        List<String> mutable = jdbc().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'login_attempts' "
                        + "AND COLUMN_NAME IN ('updated_at', 'updated_by', 'deleted')",
                String.class, database());

        assertThat(mutable).isEmpty();
    }

    @Test
    @DisplayName("sessions cascade on user delete but tokens never orphan")
    void sessionForeignKeysBehaveCorrectly() {
        List<String> rules = jdbc().queryForList("""
                SELECT CONCAT(r.TABLE_NAME, ':', r.DELETE_RULE)
                FROM information_schema.REFERENTIAL_CONSTRAINTS r
                WHERE r.CONSTRAINT_SCHEMA = ?
                  AND r.TABLE_NAME IN ('user_sessions', 'mfa_factors',
                                       'mfa_recovery_codes', 'account_tokens')
                  AND r.REFERENCED_TABLE_NAME = 'users'
                """, String.class, database());

        assertThat(rules).allMatch(rule -> rule.endsWith(":CASCADE"));
    }

    @Test
    @DisplayName("login_attempts keeps the attempt when the account is removed")
    void loginAttemptsSurviveUserRemoval() {
        // The record of who tried to reach an account must outlive the account,
        // otherwise deleting a user erases the evidence about them.
        String rule = jdbc().queryForObject("""
                SELECT r.DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS r
                WHERE r.CONSTRAINT_SCHEMA = ? AND r.TABLE_NAME = 'login_attempts'
                  AND r.REFERENCED_TABLE_NAME = 'users'
                """, String.class, database());

        assertThat(rule).isEqualTo("SET NULL");
    }

    @Test
    @DisplayName("a user holds at most one factor of each type")
    void oneFactorPerTypePerUser() {
        List<String> constraints = jdbc().queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'mfa_factors' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, database());

        assertThat(constraints).contains("uk_mfa_factors_user_type");
    }

    @Test
    @DisplayName("the user status column exists and defaults to ACTIVE")
    void userStatusExists() {
        String defaultValue = jdbc().queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status'",
                String.class, database());

        assertThat(defaultValue).contains("ACTIVE");
    }
}
