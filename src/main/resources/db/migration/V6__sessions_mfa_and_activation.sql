-- =====================================================================
-- V6 - sessions, second factor, account activation and sign-in attempts
--
-- WHY SESSIONS ARE A TABLE
--   The refresh token lived in a column on the user row. One column means one
--   session, so revoking a lost phone signed the user out of every device, and
--   there was no way to answer "where is this account signed in from".
--
-- REFRESH TOKEN ROTATION AND REUSE DETECTION
--   Each refresh mints a new token and marks the old one replaced. Every token
--   descended from one sign-in shares a family_id.
--
--   If a token that was already replaced is presented again, one of two things
--   happened: the legitimate client retried after a dropped response, or a
--   stolen token is being used alongside the real one. The two are
--   indistinguishable from the server, so the whole family is revoked and the
--   user signs in again. Losing a session occasionally is a far better outcome
--   than letting a stolen token run for its full seven days.
--
-- WHY TOKENS ARE STORED HASHED
--   A read-only leak of this table, through a backup, a log or a query, must
--   not hand over working credentials. Only SHA-256 hashes are stored, so the
--   table can be read without becoming an authentication bypass.
--
-- ON login_attempts
--   Append-only. It is the evidence for lockout, rate limiting and any later
--   question about who tried to reach an account. Rows are never edited.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Sessions
-- ---------------------------------------------------------------------
CREATE TABLE user_sessions (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    public_id               VARCHAR(26)  NULL,
    created_at              DATETIME(6)  NOT NULL,
    created_by              VARCHAR(100) NULL,
    updated_at              DATETIME(6)  NULL,
    updated_by              VARCHAR(100) NULL,

    user_id                 BIGINT       NOT NULL,

    -- Shared by every token descended from one sign-in. Revoking the family
    -- ends the whole chain, not just the token that was presented.
    family_id               VARCHAR(26)  NOT NULL,

    -- SHA-256 of the refresh token. The token itself is never stored.
    refresh_token_hash      VARCHAR(64)  NOT NULL,

    device_label            VARCHAR(120) NULL,
    device_fingerprint      VARCHAR(64)  NULL,
    ip_address              VARCHAR(45)  NULL,
    user_agent              VARCHAR(500) NULL,

    issued_at               DATETIME(6)  NOT NULL,
    -- Advanced on every authenticated request. Inactivity timeout is measured
    -- from here, not from issued_at, so an active session is not cut off at a
    -- fixed interval while an abandoned one still expires.
    last_seen_at            DATETIME(6)  NOT NULL,
    expires_at              DATETIME(6)  NOT NULL,

    revoked_at              DATETIME(6)  NULL,
    revoked_by              VARCHAR(100) NULL,
    revoked_reason          VARCHAR(200) NULL,

    -- Set when this token is rotated. A request presenting a replaced token is
    -- the reuse signal that revokes the family.
    replaced_at             DATETIME(6)  NULL,
    replaced_by_session_id  BIGINT       NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sessions_public_id (public_id),
    UNIQUE KEY uk_user_sessions_token (refresh_token_hash),
    KEY idx_user_sessions_user (user_id, revoked_at),
    KEY idx_user_sessions_family (family_id),
    KEY idx_user_sessions_expiry (expires_at),
    CONSTRAINT fk_user_sessions_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_sessions_replaced FOREIGN KEY (replaced_by_session_id) REFERENCES user_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Second factor
--
-- Required for staff and centre administrators. The shared secret is stored
-- encrypted rather than in clear: a leak of this table would otherwise let an
-- attacker generate valid codes indefinitely, which is worse than a password
-- leak because nobody would know to rotate it.
-- ---------------------------------------------------------------------
CREATE TABLE mfa_factors (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         VARCHAR(26)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100) NULL,
    updated_at        DATETIME(6)  NULL,
    updated_by        VARCHAR(100) NULL,

    user_id           BIGINT       NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    secret_encrypted  VARCHAR(512) NOT NULL,
    digits            INT          NOT NULL DEFAULT 6,
    period_seconds    INT          NOT NULL DEFAULT 30,

    -- Null until the user proves they can generate a code. An unverified
    -- factor never satisfies a challenge, so a half-finished enrolment cannot
    -- lock the account out.
    verified_at       DATETIME(6)  NULL,
    is_active         BIT(1)       NOT NULL DEFAULT b'0',
    last_used_at      DATETIME(6)  NULL,

    -- Guards against brute-forcing a six-digit code.
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      DATETIME(6)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_mfa_factors_public_id (public_id),
    UNIQUE KEY uk_mfa_factors_user_type (user_id, type),
    CONSTRAINT fk_mfa_factors_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Single-use codes for a lost authenticator. Hashed, because a leak of these
-- is a leak of the second factor.
CREATE TABLE mfa_recovery_codes (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   VARCHAR(26)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100) NULL,
    updated_at  DATETIME(6)  NULL,
    updated_by  VARCHAR(100) NULL,
    user_id     BIGINT       NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    used_at     DATETIME(6)  NULL,
    used_ip     VARCHAR(45)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mfa_recovery_public_id (public_id),
    UNIQUE KEY uk_mfa_recovery_hash (code_hash),
    KEY idx_mfa_recovery_user (user_id, used_at),
    CONSTRAINT fk_mfa_recovery_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Account tokens: invitation activation and password reset
--
-- One table, two purposes, because the rules are identical: single use,
-- time limited, stored hashed, invalidated when a newer one is issued.
--
-- A password is never emailed. The invitation carries a link that lets the
-- recipient choose their own, which means the credential is never sitting in
-- an inbox or a mail server backup for the life of the account.
-- ---------------------------------------------------------------------
CREATE TABLE account_tokens (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    public_id    VARCHAR(26)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    created_by   VARCHAR(100) NULL,
    updated_at   DATETIME(6)  NULL,
    updated_by   VARCHAR(100) NULL,

    user_id      BIGINT       NOT NULL,
    purpose      VARCHAR(30)  NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    used_at      DATETIME(6)  NULL,
    used_ip      VARCHAR(45)  NULL,
    issued_by    VARCHAR(100) NULL,
    issued_ip    VARCHAR(45)  NULL,
    invalidated_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_account_tokens_public_id (public_id),
    UNIQUE KEY uk_account_tokens_hash (token_hash),
    KEY idx_account_tokens_user (user_id, purpose, used_at),
    KEY idx_account_tokens_expiry (expires_at),
    CONSTRAINT fk_account_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Sign-in attempts. Append-only: no updated_at, no soft delete.
--
-- username_attempted holds what was typed, not a resolved account, because
-- attempts against usernames that do not exist are the interesting ones.
-- ---------------------------------------------------------------------
CREATE TABLE login_attempts (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(26)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          VARCHAR(100) NULL,

    username_attempted  VARCHAR(150) NOT NULL,
    user_id             BIGINT       NULL,
    outcome             VARCHAR(30)  NOT NULL,
    failure_reason      VARCHAR(200) NULL,
    ip_address          VARCHAR(45)  NULL,
    user_agent          VARCHAR(500) NULL,
    attempted_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_login_attempts_public_id (public_id),
    KEY idx_login_attempts_username (username_attempted, attempted_at),
    KEY idx_login_attempts_ip (ip_address, attempted_at),
    KEY idx_login_attempts_user (user_id, attempted_at),
    CONSTRAINT fk_login_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- Account state the invitation flow needs
-- ---------------------------------------------------------------------
ALTER TABLE users
    -- Proven by clicking the invitation link. An account whose address was
    -- never confirmed cannot receive a password reset, which would otherwise
    -- let a typo in an email address become a way in.
    ADD COLUMN email_verified_at DATETIME(6) NULL AFTER email,
    ADD COLUMN invited_at        DATETIME(6) NULL AFTER activated_at,
    ADD COLUMN invited_by        VARCHAR(100) NULL AFTER invited_at,
    ADD COLUMN last_password_reset_at DATETIME(6) NULL AFTER password_changed_at;

-- An invited account exists but cannot authenticate until activation sets a
-- password. Existing accounts are unaffected.
ALTER TABLE users
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER is_active;

UPDATE users SET status = 'ACTIVE' WHERE is_active = 1;
UPDATE users SET status = 'DEACTIVATED' WHERE is_active = 0;

CREATE INDEX idx_users_status ON users (status);
