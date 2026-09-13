package com.fnph.telepsychiatric.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Scope semantics, without needing a database. */
class TenantScopeTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the default scope is constrained, not unrestricted")
    void defaultsToConstrained() {
        // If this ever inverts, every tenant check in the system fails open at
        // once and nothing else in this file would catch it.
        assertThat(TenantContext.current().isConstrained()).isTrue();
        assertThat(TenantContext.current().centreId()).isNull();
    }

    @Test
    @DisplayName("a centre scope is constrained to that centre")
    void centreScopeIsConstrained() {
        TenantContext.set(TenantScope.centre(42L));
        assertThat(TenantContext.current().isConstrained()).isTrue();
        assertThat(TenantContext.current().centreId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a hospital scope is unrestricted and carries a stated reason")
    void hospitalScopeCarriesReason() {
        TenantContext.set(TenantScope.hospital("Hub Coordinator approving bookings"));
        assertThat(TenantContext.current().isConstrained()).isFalse();
        assertThat(TenantContext.current().reason()).contains("Hub Coordinator");
    }

    @Test
    @DisplayName("clearing returns to the constrained default")
    void clearFailsClosed() {
        TenantContext.set(TenantScope.hospital("temporary"));
        TenantContext.clear();
        assertThat(TenantContext.current().isConstrained()).isTrue();
    }

    @Test
    @DisplayName("widening restores the previous scope even when the block throws")
    void wideningIsExceptionSafe() {
        // Without the finally, an exception inside a widened block leaves the
        // thread unrestricted, and the servlet container hands that thread to
        // the next request.
        TenantContext.set(TenantScope.centre(7L));
        try {
            TenantContext.runAcrossAllCentres("job", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // intended
        }
        assertThat(TenantContext.current().centreId()).isEqualTo(7L);
        assertThat(TenantContext.current().isConstrained()).isTrue();
    }
}
