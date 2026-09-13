package com.fnph.telepsychiatric.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByPublicId(String publicId);

    Optional<NotificationTemplate> findByNotificationTypeAndChannelAndIsActiveTrue(
            NotificationType type, NotificationChannel channel);

    List<NotificationTemplate> findAllByOrderByNotificationTypeAsc();
}
