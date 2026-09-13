package com.fnph.telepsychiatric.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationBroadcastRepository
        extends JpaRepository<NotificationBroadcast, Long> {

    List<NotificationBroadcast> findTop50ByOrderBySentAtDesc();
}
