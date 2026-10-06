package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.common.PublicId;
import com.fnph.telepsychiatric.consultation.Modality;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hub Coordinator corrections to reviewer notes and follow-up logistics.
 *
 * <h2>What the hub may change</h2>
 * Review: the reviewer's notes, and the query text when a query was raised.
 * Follow-up: preferred date and time, mode, status, scheduled and completed
 * dates, coordination notes.
 *
 * <h2>What the hub may not change</h2>
 * Anything a doctor authored: the review outcome, prescription items,
 * investigation panels, the follow-up recommendation, interval and timeframe,
 * and signed clinical notes. A prescription anyone but the prescriber can
 * change is not a prescription. Those go back to the doctor.
 *
 * <h2>Every edit</h2>
 * Needs a reason, refuses to overwrite a change made since the editor loaded
 * the record, writes one revision row per changed field, and records an audit
 * event naming the fields without copying clinical text into the audit log.
 */
@Service
@RequiredArgsConstructor
public class HubClinicalEditService {

    private static final int MAX_TEXT = 10_000;

    private final AppointmentRepository appointmentRepository;
    private final ReleaseBundleRepository bundleRepository;
    private final ProfessionalReviewRepository reviewRepository;
    private final FollowUpRepository followUpRepository;
    private final ClinicalEditRevisionRepository revisionRepository;
    private final InAppNotificationService notificationService;
    private final AuditService auditService;

    public record ReviewEdit(
            String notes,
            String queryDetail,
            String reason,
            LocalDateTime expectedUpdatedAt) {
    }

    public record FollowUpEdit(
            LocalDate preferredDate,
            LocalTime preferredTime,
            Modality consultationMode,
            FollowUpStatus status,
            LocalDate scheduledDate,
            LocalDate completedDate,
            String notes,
            String reason,
            LocalDateTime expectedUpdatedAt) {
    }

    // -----------------------------------------------------------------
    // Reviews
    // -----------------------------------------------------------------

    @Transactional
    public ProfessionalReview editReview(
            String appointmentPublicId,
            String reviewPublicId,
            ReviewEdit edit) {

        Appointment appointment = appointment(appointmentPublicId);

        ProfessionalReview review = reviewRepository
                .findAllByBundleId(bundleId(appointment))
                .stream()
                .filter(r -> r.getPublicId().equals(reviewPublicId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "That review is not part of this consultation."));

        if (review.getSubmittedAt() == null) {
            throw new ClinicalService.ClinicalException(
                    "This review is still with the reviewer. It can be edited once submitted.");
        }

        requireUnchanged(review.getUpdatedAt(), edit.expectedUpdatedAt());
        String reason = reason(edit.reason());

        String notes = text(edit.notes(), "Notes");
        String queryDetail = text(edit.queryDetail(), "Query");

        if (!Boolean.TRUE.equals(review.getQueryRaised()) && queryDetail != null) {
            throw new IllegalArgumentException(
                    "This review raised no query, so there is no query text to edit.");
        }

        boolean queryRaised = Boolean.TRUE.equals(review.getQueryRaised());

        Changes changes = new Changes();
        changes.compare("notes", review.getNotes(), notes);
        if (queryRaised) {
            changes.compare("queryDetail", review.getQueryDetail(), queryDetail);
        }
        changes.requireAny();

        review.setNotes(notes);
        if (queryRaised) {
            review.setQueryDetail(queryDetail);
        }
        reviewRepository.save(review);

        changes.write(appointment, ClinicalEditRevision.Target.REVIEW,
                review.getId(), review.getPublicId(), reason);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.HUB_REVIEW_EDITED)
                .entityType("ProfessionalReview")
                .entityId(review.getId())
                .details("Hub edited " + String.join(", ", changes.fields())
                        + " on appointment " + appointment.getReference())
                .reason(reason)
                .build());

        if (review.getReviewer() != null) {
            String document = review.getPrescription() != null
                    ? "prescription " + review.getPrescription().getIssueNumber()
                    : review.getInvestigation() != null
                    ? "investigation " + review.getInvestigation().getIssueNumber()
                    : "a consultation document";

            notificationService.notifyUser(
                    review.getReviewer(),
                    NotificationType.CLINICAL_RECORD_EDITED,
                    "Your review was edited by the Hub Coordinator",
                    "Your review of " + document + " was edited. Reason: " + reason,
                    null,
                    "ProfessionalReview",
                    review.getId());
        }

        return review;
    }

    // -----------------------------------------------------------------
    // Follow-ups
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FollowUp> followUps(String appointmentPublicId) {
        Appointment appointment = appointment(appointmentPublicId);
        return bundleRepository.findByAppointmentId(appointment.getId())
                .map(bundle -> followUpRepository.findAllByBundleId(bundle.getId()))
                .orElse(List.of());
    }

    @Transactional
    public FollowUp editFollowUp(
            String appointmentPublicId,
            String followUpPublicId,
            FollowUpEdit edit) {

        Appointment appointment = appointment(appointmentPublicId);

        FollowUp followUp = followUpRepository.findAllByBundleId(bundleId(appointment))
                .stream()
                .filter(f -> f.getPublicId().equals(followUpPublicId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "That follow-up is not part of this consultation."));

        if (followUp.getStatus() == FollowUpStatus.NOT_REQUIRED) {
            throw new ClinicalService.ClinicalException(
                    "The doctor recorded no follow-up as needed. That decision stays with the doctor.");
        }

        requireUnchanged(followUp.getUpdatedAt(), edit.expectedUpdatedAt());
        String reason = reason(edit.reason());

        FollowUpStatus status = edit.status() == null ? followUp.getStatus() : edit.status();
        validateFollowUp(status, edit);

        String notes = text(edit.notes(), "Notes");
        Modality mode = edit.consultationMode() == null
                ? followUp.getConsultationMode()
                : edit.consultationMode();

        Changes changes = new Changes();
        changes.compare("preferredDate", followUp.getPreferredDate(), edit.preferredDate());
        changes.compare("preferredTime", followUp.getPreferredTime(), edit.preferredTime());
        changes.compare("consultationMode", followUp.getConsultationMode(), mode);
        changes.compare("status", followUp.getStatus(), status);
        changes.compare("scheduledDate", followUp.getScheduledDate(), edit.scheduledDate());
        changes.compare("completedDate", followUp.getCompletedDate(), edit.completedDate());
        changes.compare("notes", followUp.getNotes(), notes);
        changes.requireAny();

        followUp.setPreferredDate(edit.preferredDate());
        followUp.setPreferredTime(edit.preferredTime());
        followUp.setConsultationMode(mode);
        followUp.setStatus(status);
        followUp.setScheduledDate(edit.scheduledDate());
        followUp.setCompletedDate(edit.completedDate());
        followUp.setNotes(notes);
        followUpRepository.save(followUp);

        changes.write(appointment, ClinicalEditRevision.Target.FOLLOW_UP,
                followUp.getId(), followUp.getPublicId(), reason);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.HUB_FOLLOW_UP_EDITED)
                .entityType("FollowUp")
                .entityId(followUp.getId())
                .details("Hub edited " + String.join(", ", changes.fields())
                        + " on appointment " + appointment.getReference())
                .reason(reason)
                .build());

        return followUp;
    }

    private static void validateFollowUp(FollowUpStatus status, FollowUpEdit edit) {
        if (status == FollowUpStatus.NOT_REQUIRED) {
            throw new IllegalArgumentException(
                    "Only the doctor can record a follow-up as not required.");
        }
        if (status == FollowUpStatus.SCHEDULED && edit.scheduledDate() == null) {
            throw new IllegalArgumentException("A scheduled follow-up needs a scheduled date.");
        }
        if (status == FollowUpStatus.COMPLETED && edit.completedDate() == null) {
            throw new IllegalArgumentException("A completed follow-up needs a completed date.");
        }
        if (edit.completedDate() != null && edit.completedDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The completed date cannot be in the future.");
        }
    }

    // -----------------------------------------------------------------
    // History
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ClinicalEditRevision> history(String appointmentPublicId) {
        return revisionRepository.findAllByAppointmentIdOrderByCreatedAtDescIdAsc(
                appointment(appointmentPublicId).getId());
    }

    // -----------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------

    private Appointment appointment(String publicId) {
        return appointmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
    }

    private Long bundleId(Appointment appointment) {
        return bundleRepository.findByAppointmentId(appointment.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "This consultation has no clinical bundle yet."))
                .getId();
    }

    /** Refuses to overwrite a change someone else saved after this editor loaded the record. */
    private static void requireUnchanged(LocalDateTime current, LocalDateTime expected) {
        if (!Objects.equals(current, expected)) {
            throw new ClinicalService.ClinicalException(
                    "This record changed after you opened it. Reload and make your edit again.");
        }
    }

    private static String reason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.length() < 10) {
            throw new IllegalArgumentException(
                    "Say why this is being changed, in at least 10 characters.");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason under 500 characters.");
        }
        return reason;
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException(label + " is too long.");
        }
        return trimmed;
    }

    private String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    /** Collects changed fields, then writes them as one edit group. */
    private final class Changes {
        private final List<String[]> rows = new ArrayList<>();

        void compare(String field, Object before, Object after) {
            if (!Objects.equals(before, after)) {
                rows.add(new String[]{field, stringOf(before), stringOf(after)});
            }
        }

        void requireAny() {
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Nothing was changed.");
            }
        }

        List<String> fields() {
            return rows.stream().map(r -> r[0]).toList();
        }

        void write(Appointment appointment, ClinicalEditRevision.Target target,
                   Long targetId, String targetPublicId, String reason) {

            String group = PublicId.generate();
            String username = CurrentUser.usernameOrSystem();
            String displayName = CurrentUser.get()
                    .map(SecurityUser::getDisplayName)
                    .orElse(null);

            for (String[] row : rows) {
                ClinicalEditRevision revision = new ClinicalEditRevision();
                revision.setAppointmentId(appointment.getId());
                revision.setTargetType(target);
                revision.setTargetId(targetId);
                revision.setTargetPublicId(targetPublicId);
                revision.setFieldName(row[0]);
                revision.setOldValue(row[1]);
                revision.setNewValue(row[2]);
                revision.setReason(reason);
                revision.setEditedBy(username);
                revision.setEditedByName(displayName);
                revision.setEditGroup(group);
                revisionRepository.save(revision);
            }
        }
    }
}
