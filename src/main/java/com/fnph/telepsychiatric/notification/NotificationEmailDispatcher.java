package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.email.AccountEmailService;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends each notification by email as well as in-app.
 *
 * Notifications were only ever written to the in-app inbox. Email templates
 * could be edited in administration but nothing sent them, so approvals,
 * rejections, released documents and broadcasts never reached anyone's mail.
 *
 * Rules:
 *  - runs after the transaction that created the notification commits, so a
 *    rolled-back action sends nothing, and runs asynchronously so a slow mail
 *    server never delays the request
 *  - the text is the notification's own subject and body, which the services
 *    already write without clinical detail; an active EMAIL template for the
 *    type replaces them ({title} and {message} are substituted)
 *  - nothing marked as containing clinical detail is emailed
 *  - a patient is emailed at the address on the patient record; patient
 *    accounts created by online enrolment carry a placeholder address
 *  - placeholder (.local) addresses, inactive and deleted accounts are skipped
 *  - the recipient's preferences are respected: email switched off, or the
 *    category for this type switched off, means no email
 *  - a failure is logged and never affects the action that raised it
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEmailDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final AccountEmailService emailService;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    @Value("${application.portal-url:http://localhost:5173}")
    private String portalUrl;

    @Value("${application.notifications.email-enabled:true}")
    private boolean enabled;

    record Outgoing(String to, String name, String subject, String message, String link) {
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        if (!enabled) {
            return;
        }
        List<Outgoing> outgoing;
        try {
            TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
            readOnly.setReadOnly(true);
            outgoing = readOnly.execute(status -> resolve(event.notificationId()));
        } catch (Exception e) {
            log.error("Could not prepare email for notification {}: {}", event.notificationId(), e.getMessage());
            return;
        }
        if (outgoing == null || outgoing.isEmpty()) {
            log.debug("Notification {} has no email recipients", event.notificationId());
            return;
        }
        for (Outgoing mail : outgoing) {
            emailService.sendNotification(mail.to(), mail.name(), mail.subject(), mail.message(), mail.link());
        }
        log.info("Notification {} queued for email to {} recipient(s)", event.notificationId(), outgoing.size());
    }

    private List<Outgoing> resolve(Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || Boolean.TRUE.equals(notification.getContainsClinicalDetail())) {
            return List.of();
        }

        String subject = notification.getSubject();
        String message = notification.getBody();
        var template = templateRepository.findByNotificationTypeAndChannelAndIsActiveTrue(
                notification.getType(), NotificationChannel.EMAIL);
        if (template.isPresent() && !Boolean.TRUE.equals(template.get().getContainsClinicalDetail())) {
            String title = subject == null ? "" : subject;
            String body = message == null ? "" : message;
            if (template.get().getSubject() != null && !template.get().getSubject().isBlank()) {
                subject = template.get().getSubject().replace("{title}", title).replace("{message}", body);
            }
            if (template.get().getBody() != null && !template.get().getBody().isBlank()) {
                message = template.get().getBody().replace("{title}", title).replace("{message}", body);
            }
        }
        String link = notification.getActionUrl() == null ? null
                : notification.getActionUrl().startsWith("/")
                ? portalUrl.replaceAll("/+$", "") + notification.getActionUrl()
                : notification.getActionUrl();

        // Keyed by address so nobody is emailed twice for one notification.
        Map<String, Outgoing> byAddress = new LinkedHashMap<>();
        if (notification.getPatient() != null) {
            Patient patient = notification.getPatient();
            Users account = notification.getUser();
            if (account == null || allowed(account, notification.getType())) {
                add(byAddress, patient.getEmail(), patient.getFirstName(), subject, message, link);
            }
        } else if (notification.getUser() != null) {
            Users user = notification.getUser();
            if (usable(user) && allowed(user, notification.getType())) {
                add(byAddress, user.getEmail(), user.getFirstName(), subject, message, link);
            }
        } else if (notification.getTargetRole() != null) {
            Long centreId = notification.getCentre() == null ? null : notification.getCentre().getId();
            for (Users user : userRepository.findActiveByRoleCode(notification.getTargetRole().getCode())) {
                if (centreId != null && (user.getCentre() == null || !centreId.equals(user.getCentre().getId()))) {
                    continue;
                }
                if (usable(user) && allowed(user, notification.getType())) {
                    add(byAddress, user.getEmail(), user.getFirstName(), subject, message, link);
                }
            }
        }
        return new ArrayList<>(byAddress.values());
    }

    private static void add(Map<String, Outgoing> byAddress, String email, String name,
                            String subject, String message, String link) {
        if (!deliverable(email)) {
            return;
        }
        byAddress.putIfAbsent(email.trim().toLowerCase(Locale.ROOT),
                new Outgoing(email.trim(), name, subject, message, link));
    }

    private static boolean usable(Users user) {
        return Boolean.TRUE.equals(user.getIsActive()) && !Boolean.TRUE.equals(user.getDeleted());
    }

    /** A real address: present, shaped like one, and not a placeholder domain. */
    static boolean deliverable(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return false;
        }
        String lower = email.trim().toLowerCase(Locale.ROOT);
        return !lower.endsWith(".local") && !lower.endsWith(".invalid") && !lower.endsWith(".example");
    }

    /** The recipient's email preference, and the category switch for this type. */
    private boolean allowed(Users user, NotificationType type) {
        List<NotificationPreference> found = entityManager.createQuery(
                        "select p from NotificationPreference p where p.user.id = :userId",
                        NotificationPreference.class)
                .setParameter("userId", user.getId())
                .setMaxResults(1)
                .getResultList();
        if (found.isEmpty()) {
            return true;
        }
        NotificationPreference p = found.get(0);
        if (Boolean.FALSE.equals(p.getEmailEnabled())) {
            return false;
        }
        return switch (category(type)) {
            case APPOINTMENTS -> !Boolean.FALSE.equals(p.getAppointmentReminders());
            case PAYMENTS -> !Boolean.FALSE.equals(p.getPaymentNotifications());
            case CLINICAL -> !Boolean.FALSE.equals(p.getClinicalUpdates());
            case SYSTEM -> !Boolean.FALSE.equals(p.getSystemAlerts());
        };
    }

    enum Category { APPOINTMENTS, PAYMENTS, CLINICAL, SYSTEM }

    static Category category(NotificationType type) {
        return switch (type) {
            case PAYMENT_SUCCESS, PAYMENT_FAILURE, CREDIT_ISSUED, PAYMENT_AMOUNT_MISMATCH,
                 CENTRE_WALLET_ALERT -> Category.PAYMENTS;
            case PRESCRIPTION_RELEASED, INVESTIGATION_RELEASED, FOLLOW_UP_RECOMMENDED,
                 CONSULTATION_READY -> Category.CLINICAL;
            case SYSTEM_ALERT, SUPPORT_TICKET_UPDATE, EHR_VERIFICATION_RESULT -> Category.SYSTEM;
            default -> Category.APPOINTMENTS;
        };
    }
}