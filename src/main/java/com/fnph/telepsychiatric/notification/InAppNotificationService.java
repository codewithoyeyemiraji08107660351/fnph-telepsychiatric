package com.fnph.telepsychiatric.notification;

import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * In-app notifications, delivered to a person or to a dashboard.
 *
 * <h2>Why a role can be the addressee</h2>
 *
 * A booking arriving for approval is not addressed to one Hub Coordinator. It
 * is addressed to whoever is on the desk. Fanning it out to every holder of the
 * role would produce five rows for one piece of work and a count that is wrong
 * the moment one of them acts on it; picking one holder would send it to
 * whoever happens to be on leave.
 *
 * So the notification targets the role, and any holder can acknowledge it. The
 * dashboard count is then the amount of work waiting, which is the number a
 * coordinator actually needs.
 *
 * <h2>No clinical detail</h2>
 *
 * The same rule as email. A notification says a prescription is ready, never
 * what it is for. Anything more requires an authenticated session and the
 * permission to read the record itself, because a notification list is visible
 * on a shared clinic screen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationService {

    private final NotificationRepository notificationRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    /** Addressed to one person: their appointment, their document, their payment. */
    @Transactional
    public Notification notifyUser(Users user, NotificationType type,
                                   String subject, String body, String actionUrl,
                                   String entityType, Long entityId) {
        Notification notification = base(type, subject, body, actionUrl, entityType, entityId);
        notification.setUser(user);
        return notificationRepository.save(notification);
    }

    /**
     * Addressed to a dashboard.
     *
     * @param centre restricts it to one centre's holders of the role, for
     *               centre-scoped work. Null for FNPH roles.
     */
    @Transactional
    public Notification notifyRole(String roleCode, Center centre, NotificationType type,
                                   String subject, String body, String actionUrl,
                                   String entityType, Long entityId) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalArgumentException("No role " + roleCode));

        Notification notification = base(type, subject, body, actionUrl, entityType, entityId);
        notification.setTargetRole(role);
        notification.setCentre(centre);
        return notificationRepository.save(notification);
    }

    @Transactional
    public Notification notifyPatient(Patient patient, NotificationType type,
                                      String subject, String body, String actionUrl,
                                      String entityType, Long entityId) {
        Notification notification = base(type, subject, body, actionUrl, entityType, entityId);
        notification.setPatient(patient);
        userRepository.findByPatient_EhrNumber(patient.getEhrNumber())
                .ifPresent(notification::setUser);
        return notificationRepository.save(notification);
    }

    /**
     * Everything waiting for this principal: their own, plus their dashboard's.
     *
     * Centre-scoped roles see only their own centre's items, which the query
     * enforces rather than leaving to the caller.
     */
    @Transactional(readOnly = true)
    public List<Notification> inbox(Long userId, List<String> roleCodes, Long centreId,
                                    boolean unreadOnly) {
        return notificationRepository.findInbox(userId, roleCodes, centreId, unreadOnly);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId, List<String> roleCodes, Long centreId) {
        return notificationRepository.countUnread(userId, roleCodes, centreId);
    }

    /**
     * Marks one item read, recording who did it.
     *
     * For a role-targeted item that is the point: a coordinator opening a
     * booking request takes it off everyone's list and the record shows who
     * picked it up.
     */
    @Transactional
    public void markRead(String notificationPublicId) {
        notificationRepository.findByPublicId(notificationPublicId).ifPresent(notification -> {
            if (notification.getReadAt() == null) {
                notification.setReadAt(LocalDateTime.now());
                notification.setStatus(DeliveryStatus.READ);
                CurrentUser.get().ifPresent(principal ->
                        notification.setAcknowledgedBy(
                                userRepository.findById(principal.getUserId()).orElse(null)));
                notificationRepository.save(notification);
            }
        });
    }

    @Transactional
    public int markAllRead(Long userId, List<String> roleCodes, Long centreId) {
        return notificationRepository.markInboxRead(userId, roleCodes, centreId, LocalDateTime.now());
    }

    private Notification base(NotificationType type, String subject, String body,
                              String actionUrl, String entityType, Long entityId) {
        Notification notification = new Notification();
        notification.setType(type);
        notification.setChannel(NotificationChannel.IN_APP);
        notification.setStatus(DeliveryStatus.DELIVERED);
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setActionUrl(actionUrl);
        notification.setEntityType(entityType);
        notification.setEntityId(entityId);
        // In-app is behind authentication, so this is always false here. The
        // flag matters for push and SMS, which appear on a locked screen.
        notification.setContainsClinicalDetail(false);
        notification.setSentAt(LocalDateTime.now());
        notification.setDeliveredAt(LocalDateTime.now());
        return notification;
    }
}
