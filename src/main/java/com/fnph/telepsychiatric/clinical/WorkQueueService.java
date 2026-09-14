package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The Nursing and HIM preparation queues.
 *
 * <h2>Why an exception state exists</h2>
 *
 * HIM retrieves a paper record that sometimes cannot be found, and Nursing
 * records vitals that a patient sometimes has not submitted. Without a way to
 * say so, the only options are leaving the item untreated forever or marking it
 * treated untruthfully. The second is what actually happens, and it means a
 * clinician opens a session believing the record is on the desk.
 *
 * An exception is visible to the Hub Coordinator, which is the point: it is a
 * handover, not a dismissal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkQueueService {

    private final AppointmentRepository appointmentRepository;
    private final VitalsService vitalsService;
    private final AuditService auditService;

    public enum Queue { NURSING, HIM }

    /** Picks the item up. Makes it visible that somebody is on it. */
    @Transactional
    public Appointment start(String appointmentPublicId, Queue queue) {
        Appointment appointment = requireAssigned(appointmentPublicId, queue);
        LocalDateTime now = LocalDateTime.now();

        if (queue == Queue.NURSING) {
            if (appointment.getNursingCompletedAt() != null) {
                return appointment;
            }
            appointment.setNursingStartedAt(now);
            appointment.setNursingExceptionAt(null);
            appointment.setNursingExceptionReason(null);
        } else {
            if (appointment.getHimCompletedAt() != null) {
                return appointment;
            }
            appointment.setHimStartedAt(now);
            appointment.setHimExceptionAt(null);
            appointment.setHimExceptionReason(null);
        }
        return appointmentRepository.save(appointment);
    }

    /**
     * Marks preparation complete.
     *
     * Nursing cannot complete without vitals on the record. Module 2 makes
     * vitals mandatory before a consultation, and a nurse marking preparation
     * complete is the assertion that they are in the offline EHR. Asserting it
     * with nothing recorded is the failure this refuses.
     */
    @Transactional
    public Appointment complete(String appointmentPublicId, Queue queue, String notes) {
        Appointment appointment = requireAssigned(appointmentPublicId, queue);
        LocalDateTime now = LocalDateTime.now();

        if (queue == Queue.NURSING) {
            if (vitalsService.forAppointment(appointment.getId()).isEmpty()) {
                throw new ClinicalService.ClinicalException(
                        "No vitals are recorded for this appointment. Record them, or raise "
                                + "an exception saying the patient did not submit them.");
            }
            if (appointment.getRoom() == null || appointment.getRoom().isBlank()) {
                throw new ClinicalService.ClinicalException(
                        "Assign the room before marking preparation complete");
            }
            appointment.setNursingCompletedAt(now);
            appointment.setNursingExceptionAt(null);
            appointment.setNursingExceptionReason(null);
        } else {
            appointment.setHimCompletedAt(now);
            appointment.setHimExceptionAt(null);
            appointment.setHimExceptionReason(null);
        }
        appointmentRepository.save(appointment);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.APPOINTMENT_UPDATED)
                .entityType("Appointment")
                .entityId(appointment.getId())
                .details("%s preparation marked complete".formatted(queue))
                .reason(notes)
                .build());

        log.info("{} queue item {} completed", queue, appointment.getReference());
        return appointment;
    }

    /**
     * Records that the work cannot be completed, and why.
     *
     * The reason is mandatory. "Exception" with no explanation tells the Hub
     * Coordinator nothing and is indistinguishable from an item nobody looked
     * at.
     */
    @Transactional
    public Appointment raiseException(String appointmentPublicId, Queue queue, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ClinicalService.ClinicalException(
                    "Say what is blocking this item. The Hub Coordinator acts on the reason.");
        }
        Appointment appointment = requireAssigned(appointmentPublicId, queue);
        LocalDateTime now = LocalDateTime.now();

        if (queue == Queue.NURSING) {
            appointment.setNursingExceptionAt(now);
            appointment.setNursingExceptionReason(reason);
        } else {
            appointment.setHimExceptionAt(now);
            appointment.setHimExceptionReason(reason);
        }
        appointmentRepository.save(appointment);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.APPOINTMENT_UPDATED)
                .entityType("Appointment")
                .entityId(appointment.getId())
                .details("%s exception raised".formatted(queue))
                .reason(reason)
                .build());

        log.warn("{} exception on {}: {}", queue, appointment.getReference(), reason);
        return appointment;
    }

    /** Treated wins over exception, exception over in progress. */
    public WorkQueueState stateOf(Appointment appointment, Queue queue) {
        LocalDateTime completed = queue == Queue.NURSING
                ? appointment.getNursingCompletedAt() : appointment.getHimCompletedAt();
        LocalDateTime exception = queue == Queue.NURSING
                ? appointment.getNursingExceptionAt() : appointment.getHimExceptionAt();
        LocalDateTime started = queue == Queue.NURSING
                ? appointment.getNursingStartedAt() : appointment.getHimStartedAt();

        if (completed != null) {
            return WorkQueueState.TREATED;
        }
        if (exception != null) {
            return WorkQueueState.EXCEPTION;
        }
        return started != null ? WorkQueueState.IN_PROGRESS : WorkQueueState.UNTREATED;
    }

    /**
     * Only the assigned person works their own item.
     *
     * Without this a nurse could mark another nurse's preparation complete,
     * and the timestamp would name the wrong person.
     */
    private Appointment requireAssigned(String publicId, Queue queue) {
        Appointment appointment = appointmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ClinicalService.ClinicalException(
                        "No such appointment"));

        Long userId = CurrentUser.require().getUserId();
        var assignee = queue == Queue.NURSING
                ? appointment.getNurse() : appointment.getHimOfficer();

        if (assignee == null || !assignee.getId().equals(userId)) {
            throw new ClinicalService.ClinicalException(
                    "That item is not assigned to you");
        }
        return appointment;
    }
}