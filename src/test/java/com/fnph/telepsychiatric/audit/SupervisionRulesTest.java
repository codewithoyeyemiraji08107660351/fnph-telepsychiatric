package com.fnph.telepsychiatric.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisionRulesTest {

    @AfterEach
    void clear() {
        SupervisionContext.clear();
    }

    @Test
    @DisplayName("no supervision is in effect by default")
    void defaultsToNoSupervision() {
        assertThat(SupervisionContext.isActive()).isFalse();
        assertThat(SupervisionContext.current()).isNull();
    }

    @Test
    @DisplayName("an active context carries both the session and the account being acted as")
    void carriesBothIdentities() {
        // The audit row needs the administrator who authenticated and the
        // account they are acting as. One without the other is unusable.
        SupervisionContext.set(new SupervisionContext.Snapshot(99L, 42L, "DOCTOR"));

        assertThat(SupervisionContext.isActive()).isTrue();
        assertThat(SupervisionContext.current().viewAsSessionId()).isEqualTo(99L);
        assertThat(SupervisionContext.current().targetUserId()).isEqualTo(42L);
        assertThat(SupervisionContext.current().targetRoleCode()).isEqualTo("DOCTOR");
    }

    @Test
    @DisplayName("clearing removes it, so a reused thread does not inherit it")
    void clearingRemovesIt() {
        // Servlet threads are reused. A context left behind would attribute the
        // next request's actions to a session that had already ended.
        SupervisionContext.set(new SupervisionContext.Snapshot(1L, 2L, "NURSING"));
        SupervisionContext.clear();

        assertThat(SupervisionContext.isActive()).isFalse();
    }
}
