package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
public class NotificationPreference extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private Users user;

    @Column(name = "email_enabled", nullable = false)
    private Boolean emailEnabled = true;

    @Column(name = "in_app_enabled", nullable = false)
    private Boolean inAppEnabled = true;

    @Column(name = "sms_enabled", nullable = false)
    private Boolean smsEnabled = false;

    @Column(name = "push_enabled", nullable = false)
    private Boolean pushEnabled = false;

    @Column(name = "appointment_reminders", nullable = false)
    private Boolean appointmentReminders = true;

    @Column(name = "payment_notifications", nullable = false)
    private Boolean paymentNotifications = true;

    @Column(name = "clinical_updates", nullable = false)
    private Boolean clinicalUpdates = true;

    @Column(name = "system_alerts", nullable = false)
    private Boolean systemAlerts = true;


}
