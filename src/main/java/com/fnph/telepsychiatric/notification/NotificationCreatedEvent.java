package com.fnph.telepsychiatric.notification;

/** Published when a notification is saved. Email delivery listens after commit. */
public record NotificationCreatedEvent(Long notificationId) {
}