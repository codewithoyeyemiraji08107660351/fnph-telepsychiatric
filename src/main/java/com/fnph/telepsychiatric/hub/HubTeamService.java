package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.Permissions;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.clinical.BundleStatus;
import com.fnph.telepsychiatric.clinical.ProfessionalReviewService;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import com.fnph.telepsychiatric.clinical.ReviewType;
import com.fnph.telepsychiatric.consultation.ConsultationRepository;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEvent;
import com.fnph.telepsychiatric.scheduling.BookingService.BookingException;
import com.fnph.telepsychiatric.scheduling.DoctorAvailabilityRepository;
import com.fnph.telepsychiatric.scheduling.TeamHistoryService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Hub Coordinator changing who is on a consultation's team after approval.
 *
 * <h2>What can change, and when</h2>
 *
 * Before approval the team is set on the approval screen, so this refuses
 * AWAITING_APPROVAL. After approval each role locks when its part of the work
 * is done, because the record must keep showing who actually did it:
 * <ul>
 *   <li>Doctor: until the session starts. Checked against the doctor's
 *       availability and their other consultations, as at approval.</li>
 *   <li>Nurse and HIM officer: until they record their part complete.</li>
 *   <li>Pharmacist and laboratory technician: until the bundle is released.
 *       Their unsubmitted reviews move with the role, including reviews that
 *       were created when nobody held it and so sat in no one's queue.</li>
 * </ul>
 * Nothing changes on a released bundle, or on a consultation that was
 * cancelled, rejected or not attended.
 *
 * Every change needs a reason, is written to the team history and the audit
 * log, and tells both the person taken off and the person put on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HubTeamService {

    public static final int REASON_MIN = 10;

    private static final Set<Status> AFTER_APPROVAL =
            EnumSet.of(Status.APPROVED, Status.IN_PROGRESS, Status.COMPLETED);

    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final DoctorAvailabilityRepository availabilityRepository;
    private final ReleaseBundleRepository bundleRepository;
    private final ConsultationRepository consultationRepository;
    private final ProfessionalReviewService reviewService;
    private final TeamHistoryService teamHistory;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    /** A team role and the account role code someone must hold to fill it. */
    public enum Role {
        DOCTOR("DOCTOR", "doctor"),
        NURSE("NURSING", "nurse"),
        PHARMACIST("PHARMACIST", "pharmacist"),
        LABORATORY("LABORATORY_TECHNICIAN", "laboratory technician"),
        HIM("HIM", "HIM officer");

        public final String roleCode;
        public final String label;

        Role(String roleCode, String label) {
            this.roleCode = roleCode;
            this.label = label;
        }
    }

    public record Change(String role, String fromName, String toName, int reviewsMoved) {
    }

    /**
     * For each role, null if it can be changed now, or the reason it cannot.
     * Ordered as the team panel shows it.
     */
    @Transactional(readOnly = true)
    public Map<Role, String> locks(Appointment appointment) {
        ReleaseBundle bundle = bundleRepository.findByAppointmentId(appointment.getId()).orElse(null);
        Map<Role, String> result = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            String lock = lockReason(appointment, bundle, role);
            if (lock == null && role == Role.DOCTOR && sessionStarted(appointment)) {
                lock = "The session has started. The doctor who held it stays on record.";
            }
            result.put(role, lock);
        }
        return result;
    }

    @Transactional
    public Change reassign(String appointmentPublicId, Role role, String userPublicId, String reason) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));

        if (role == Role.DOCTOR && !CurrentUser.get()
                .map(u -> u.hasPermission(Permissions.APPOINTMENT_ASSIGN_DOCTOR))
                .orElse(false)) {
            throw new AccessDeniedException("Changing the doctor needs appointment.assign_doctor");
        }

        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < REASON_MIN) {
            throw new IllegalArgumentException(
                    "Say why, in at least " + REASON_MIN + " characters. It is kept in the "
                            + "team history and the people affected may ask.");
        }

        ReleaseBundle bundle = bundleRepository.findByAppointmentId(appointment.getId()).orElse(null);
        String lock = lockReason(appointment, bundle, role);
        if (lock == null && role == Role.DOCTOR && sessionStarted(appointment)) {
            lock = "The session has started. The doctor who held it stays on record.";
        }
        if (lock != null) {
            throw new BookingException(lock);
        }

        Users to = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such staff member"));
        requireEligible(to, role);

        Users from = current(appointment, role);
        boolean samePerson = from != null && from.getId().equals(to.getId());

        if (role == Role.DOCTOR && !samePerson) {
            requireDoctorFree(appointment, to);
        }

        int moved = 0;
        if (!samePerson) {
            TeamHistoryService.TeamSnapshot before = TeamHistoryService.TeamSnapshot.of(appointment);
            set(appointment, role, to);
            appointmentRepository.save(appointment);
            if (role == Role.DOCTOR) {
                // A video room opened by an early join carries the doctor too.
                consultationRepository.findByAppointmentId(appointment.getId())
                        .filter(c -> c.getStartedAt() == null)
                        .ifPresent(c -> {
                            c.setDoctor(to);
                            consultationRepository.save(c);
                        });
            }
            teamHistory.record(appointment, before,
                    AppointmentTeamEvent.ChangeSource.REASSIGNMENT, trimmed);
        }

        // Reviews follow the role. When the person is unchanged this still
        // picks up reviews that were created with nobody assigned.
        if (bundle != null && (role == Role.PHARMACIST || role == Role.LABORATORY)) {
            moved = reviewService.reassignOpen(bundle.getId(),
                    role == Role.PHARMACIST ? ReviewType.PHARMACY : ReviewType.LABORATORY,
                    to, trimmed);
        }

        if (samePerson && moved == 0) {
            throw new IllegalArgumentException(
                    to.getFullName() + " is already the " + role.label + " on this consultation");
        }

        if (!samePerson) {
            notifyChange(appointment, role, from, to);
            auditService.record(AuditService.AuditEvent.builder()
                    .action(AuditAction.HUB_TEAM_REASSIGNED)
                    .entityType("Appointment")
                    .entityId(appointment.getId())
                    .details(role.label + " on " + appointment.getReference() + " changed from "
                            + (from == null ? "nobody" : from.getFullName()) + " to "
                            + to.getFullName()
                            + (moved > 0 ? "; " + moved + " open review(s) moved" : ""))
                    .reason(trimmed)
                    .build());
        }

        log.info("{} on {} -> {} by {} ({} reviews moved)", role, appointment.getReference(),
                to.getPublicId(), CurrentUser.usernameOrSystem(), moved);

        return new Change(role.name(), from == null ? null : from.getFullName(),
                to.getFullName(), moved);
    }

    // -----------------------------------------------------------------

    private static String lockReason(Appointment a, ReleaseBundle bundle, Role role) {
        Status status = a.getStatus();
        if (status == Status.AWAITING_APPROVAL) {
            return "This consultation has not been approved yet. Set the team on the approval screen.";
        }
        if (!AFTER_APPROVAL.contains(status)) {
            return "This consultation is " + status.name().toLowerCase().replace('_', ' ')
                    + ", so its team can no longer change.";
        }
        if (bundle != null && bundle.getStatus() == BundleStatus.RELEASED) {
            return "The documents have been released. The team on record stays as it was.";
        }
        return switch (role) {
            case DOCTOR -> status == Status.APPROVED ? null
                    : "The session has started. The doctor who held it stays on record.";
            case NURSE -> a.getNursingCompletedAt() != null
                    ? "The nursing assessment is complete. The nurse who did it stays on record."
                    : null;
            case HIM -> a.getHimCompletedAt() != null
                    ? "HIM work is complete. The officer who did it stays on record."
                    : null;
            case PHARMACIST, LABORATORY -> null;
        };
    }

    private boolean sessionStarted(Appointment appointment) {
        return consultationRepository.findByAppointmentId(appointment.getId())
                .map(c -> c.getStartedAt() != null)
                .orElse(false);
    }

    private void requireEligible(Users user, Role role) {
        if (!user.isEnabled()) {
            throw new IllegalArgumentException(user.getFullName() + "'s account is not active");
        }
        if (user.getCentre() != null) {
            throw new IllegalArgumentException(
                    "Centre staff cannot join a hospital consultation team");
        }
        List<String> codes = userRoleRepository.findRoleCodesByUserId(user.getId());
        if (!codes.contains(role.roleCode)) {
            throw new IllegalArgumentException(
                    user.getFullName() + " does not hold the " + role.label + " role");
        }
    }

    private void requireDoctorFree(Appointment appointment, Users doctor) {
        if (appointment.getScheduledEndAt() == null) {
            throw new BookingException("This consultation has no end time, so the doctor's "
                    + "availability cannot be checked");
        }
        if (!availabilityRepository.isAvailable(doctor.getId(),
                appointment.getAppointmentDate(), appointment.getScheduledEndAt())) {
            throw new BookingException(
                    doctor.getFullName() + " is not marked available for the whole of that slot");
        }
        if (appointmentRepository.doctorBusy(doctor.getId(), appointment.getId(),
                appointment.getAppointmentDate(), appointment.getScheduledEndAt())) {
            throw new BookingException(
                    doctor.getFullName() + " already has a consultation at that time");
        }
    }

    private static Users current(Appointment a, Role role) {
        return switch (role) {
            case DOCTOR -> a.getDoctor();
            case NURSE -> a.getNurse();
            case PHARMACIST -> a.getPharmacist();
            case LABORATORY -> a.getLaboratoryTechnician();
            case HIM -> a.getHimOfficer();
        };
    }

    private static void set(Appointment a, Role role, Users user) {
        switch (role) {
            case DOCTOR -> a.setDoctor(user);
            case NURSE -> a.setNurse(user);
            case PHARMACIST -> a.setPharmacist(user);
            case LABORATORY -> a.setLaboratoryTechnician(user);
            case HIM -> a.setHimOfficer(user);
        }
    }

    private void notifyChange(Appointment a, Role role, Users from, Users to) {
        String when = a.getReference();
        notifications.notifyUser(to, NotificationType.APPOINTMENT_ASSIGNED,
                "You have been added to a consultation team",
                "The Hub Coordinator has made you the " + role.label + " for consultation "
                        + when + ".",
                link(role), "Appointment", a.getId());
        if (from != null) {
            notifications.notifyUser(from, NotificationType.APPOINTMENT_REASSIGNED,
                    "You have been taken off a consultation team",
                    "The Hub Coordinator has moved the " + role.label + " role on consultation "
                            + when + " to a colleague. You do not need to do anything further on it.",
                    link(role), "Appointment", a.getId());
        }
    }

    private static String link(Role role) {
        return switch (role) {
            case DOCTOR -> "/clinical";
            case NURSE -> "/queues/nursing";
            case PHARMACIST -> "/reviews/pharmacy";
            case LABORATORY -> "/reviews/laboratory";
            case HIM -> "/queues/him";
        };
    }
}
