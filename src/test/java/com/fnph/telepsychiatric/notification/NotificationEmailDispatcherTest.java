package com.fnph.telepsychiatric.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailDispatcherTest {

    @Test
    void placeholderAndMalformedAddressesAreNeverEmailed() {
        assertThat(NotificationEmailDispatcher.deliverable("fatima@example.org")).isTrue();
        assertThat(NotificationEmailDispatcher.deliverable("204815@patient.fnph.local")).isFalse();
        assertThat(NotificationEmailDispatcher.deliverable("someone@test.invalid")).isFalse();
        assertThat(NotificationEmailDispatcher.deliverable("not-an-address")).isFalse();
        assertThat(NotificationEmailDispatcher.deliverable("  ")).isFalse();
        assertThat(NotificationEmailDispatcher.deliverable(null)).isFalse();
    }

    @Test
    void typesMapToThePreferenceThatCanSwitchThemOff() {
        assertThat(NotificationEmailDispatcher.category(NotificationType.PAYMENT_SUCCESS))
                .isEqualTo(NotificationEmailDispatcher.Category.PAYMENTS);
        assertThat(NotificationEmailDispatcher.category(NotificationType.PRESCRIPTION_RELEASED))
                .isEqualTo(NotificationEmailDispatcher.Category.CLINICAL);
        assertThat(NotificationEmailDispatcher.category(NotificationType.SYSTEM_ALERT))
                .isEqualTo(NotificationEmailDispatcher.Category.SYSTEM);
        assertThat(NotificationEmailDispatcher.category(NotificationType.APPOINTMENT_APPROVED))
                .isEqualTo(NotificationEmailDispatcher.Category.APPOINTMENTS);
    }
}