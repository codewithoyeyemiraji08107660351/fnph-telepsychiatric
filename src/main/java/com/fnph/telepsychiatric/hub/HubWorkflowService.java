package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.clinical.ClinicalEditRevision;
import com.fnph.telepsychiatric.clinical.ClinicalEditRevisionRepository;
import com.fnph.telepsychiatric.clinical.FollowUp;
import com.fnph.telepsychiatric.clinical.FollowUpRepository;
import com.fnph.telepsychiatric.clinical.Investigation;
import com.fnph.telepsychiatric.clinical.InvestigationRepository;
import com.fnph.telepsychiatric.clinical.Prescription;
import com.fnph.telepsychiatric.clinical.PrescriptionRepository;
import com.fnph.telepsychiatric.clinical.ProfessionalReview;
import com.fnph.telepsychiatric.clinical.ProfessionalReviewRepository;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import com.fnph.telepsychiatric.clinical.ReviewDigest;
import com.fnph.telepsychiatric.clinical.ReviewType;
import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.consultation.AttendanceEvent;
import com.fnph.telepsychiatric.consultation.AttendanceEventRepository;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.consultation.ConsultationNote;
import com.fnph.telepsychiatric.consultation.ConsultationNoteRepository;
import com.fnph.telepsychiatric.consultation.ConsultationRepository;
import com.fnph.telepsychiatric.scheduling.AppointmentStatusHistory;
import com.fnph.telepsychiatric.scheduling.AppointmentStatusHistoryRepository;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEvent;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEventRepository;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Hub Coordinator's view of consultations as journeys.
 *
 * <ul>
 *   <li>{@link #board}: every consultation in a date window, with its team,
 *       stage, review state, bundle and follow-up, in a handful of queries.</li>
 *   <li>{@link #timeline}: one consultation from booking to follow-up, merged
 *       from every record that already exists. Nothing new is stored.</li>
 * </ul>
 *
 * Read-only. FNPH pathway only; centre appointments have their own desk.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubWorkflowService {

    /** Bookings that never became a consultation and are not worth a row. */
    private static final List<Status> NOT_ON_BOARD = List.of(Status.SLOT_HELD, Status.EXPIRED);

    /** Keeps the board bounded. A quarter is enough for any operational view. */
    public static final int MAX_WINDOW_DAYS = 92;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository statusHistoryRepository;
    private final AppointmentTeamEventRepository teamEventRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationNoteRepository noteRepository;
    private final AttendanceEventRepository attendanceRepository;
    private final ReleaseBundleRepository bundleRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final ProfessionalReviewRepository reviewRepository;
    private final FollowUpRepository followUpRepository;
    private final ClinicalEditRevisionRepository editRepository;

    // =================================================================
    // Board
    // =================================================================

    public record TeamNames(String doctor, String nurse, String pharmacist,
                            String laboratory, String him, String room) {
    }

    public record ReviewSummary(int total, int pending, int unassigned, int queries) {
    }

    public record BoardRow(
            String appointmentPublicId,
            String reference,
            String patientName,
            String ehrNumber,
            LocalDateTime appointmentDate,
            String status,
            WorkflowStage stage,
            TeamNames team,
            ReviewSummary reviews,
            String bundlePublicId,
            String bundleStatus,
            String followUpStatus) {
    }

    public record Board(
            LocalDate from,
            LocalDate to,
            Map<WorkflowStage, Integer> stageCounts,
            int total,
            int page,
            int size,
            List<BoardRow> rows) {
    }

    /**
     * @param from  first hospital-local day, inclusive
     * @param to    last hospital-local day, inclusive
     * @param stage optional filter; counts are always for the whole window
     * @param query optional reference, EHR number or patient name fragment
     */
    public Board board(LocalDate from, LocalDate to, WorkflowStage stage, String query,
                       int page, int size) {
        requireWindow(from, to);

        List<Appointment> appointments = appointmentRepository.findHubWorkflow(
                HospitalClock.toUtc(from, LocalTime.MIDNIGHT),
                HospitalClock.toUtc(to.plusDays(1), LocalTime.MIDNIGHT),
                NOT_ON_BOARD);

        String needle = query == null || query.isBlank() ? null : query.trim().toLowerCase();
        if (needle != null) {
            appointments = appointments.stream().filter(a -> matches(a, needle)).toList();
        }

        Map<Long, ReleaseBundle> bundleByAppointment = appointments.isEmpty()
                ? Map.of()
                : bundleRepository.findAllByAppointmentIdIn(
                        appointments.stream().map(Appointment::getId).toList())
                .stream()
                .collect(Collectors.toMap(b -> b.getAppointment().getId(),
                        Function.identity(), (a, b) -> a));

        List<Long> bundleIds = bundleByAppointment.values().stream().map(ReleaseBundle::getId).toList();

        Map<Long, List<ReviewDigest>> reviewsByBundle = bundleIds.isEmpty()
                ? Map.of()
                : reviewRepository.findDigestsByBundleIds(bundleIds).stream()
                .filter(r -> r.bundleId() != null)
                .collect(Collectors.groupingBy(ReviewDigest::bundleId));

        Map<Long, List<FollowUp>> followUpsByBundle = bundleIds.isEmpty()
                ? Map.of()
                : followUpRepository.findAllByBundleIdIn(bundleIds).stream()
                .collect(Collectors.groupingBy(f -> f.getBundle().getId()));

        Map<WorkflowStage, Integer> counts = new EnumMap<>(WorkflowStage.class);
        for (WorkflowStage s : WorkflowStage.values()) {
            counts.put(s, 0);
        }

        List<BoardRow> rows = new ArrayList<>();
        for (Appointment a : appointments) {
            ReleaseBundle bundle = bundleByAppointment.get(a.getId());
            List<ReviewDigest> reviews = bundle == null
                    ? List.of() : reviewsByBundle.getOrDefault(bundle.getId(), List.of());
            List<FollowUp> followUps = bundle == null
                    ? List.of() : followUpsByBundle.getOrDefault(bundle.getId(), List.of());

            WorkflowStage s = WorkflowStage.of(a, bundle, reviews, followUps);
            counts.merge(s, 1, Integer::sum);
            if (stage == null || stage == s) {
                rows.add(row(a, s, bundle, reviews, followUps));
            }
        }

        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        int start = Math.min(safePage * safeSize, rows.size());
        int end = Math.min(start + safeSize, rows.size());

        return new Board(from, to, counts, rows.size(), safePage, safeSize,
                List.copyOf(rows.subList(start, end)));
    }

    private static boolean matches(Appointment a, String needle) {
        return contains(a.getReference(), needle)
                || (a.getPatient() != null
                && (contains(a.getPatient().getEhrNumber(), needle)
                || contains(patientName(a), needle)));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private static BoardRow row(Appointment a, WorkflowStage stage, ReleaseBundle bundle,
                                List<ReviewDigest> reviews, List<FollowUp> followUps) {
        ReviewSummary summary = new ReviewSummary(
                reviews.size(),
                (int) reviews.stream().filter(ReviewDigest::pending).count(),
                (int) reviews.stream().filter(ReviewDigest::unassigned).count(),
                (int) reviews.stream().filter(ReviewDigest::query).count());

        String followUpStatus = followUps.stream()
                .map(f -> f.getStatus().name())
                .findFirst()
                .orElse(null);

        return new BoardRow(
                a.getPublicId(),
                a.getReference(),
                patientName(a),
                a.getPatient() == null ? null : a.getPatient().getEhrNumber(),
                a.getAppointmentDate(),
                a.getStatus().name(),
                stage,
                team(a),
                summary,
                bundle == null ? null : bundle.getPublicId(),
                bundle == null ? null : bundle.getStatus().name(),
                followUpStatus);
    }

    private static TeamNames team(Appointment a) {
        return new TeamNames(
                name(a.getDoctor()),
                name(a.getNurse()),
                name(a.getPharmacist()),
                name(a.getLaboratoryTechnician()),
                name(a.getHimOfficer()),
                a.getAssignedRoom() == null ? a.getRoom() : a.getAssignedRoom().getCode());
    }

    // =================================================================
    // Timeline
    // =================================================================

    public enum Category { BOOKING, TEAM, SESSION, CLINICAL, REVIEW, RELEASE, FOLLOW_UP, EDIT }

    public record TimelineEvent(
            LocalDateTime at,
            Category category,
            String title,
            String detail,
            String actor) {
    }

    /** Elapsed minutes between milestones; null when either end has not happened. */
    public record Durations(
            Long bookedToApproved,
            Long approvedToSessionStart,
            Long sessionLength,
            Long sessionEndToRelease) {
    }

    public record Timeline(
            String appointmentPublicId,
            String reference,
            String patientName,
            LocalDateTime appointmentDate,
            String status,
            WorkflowStage stage,
            TeamNames team,
            Durations durations,
            List<TimelineEvent> events) {
    }

    public Timeline timeline(String appointmentPublicId) {
        Appointment a = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));

        List<TimelineEvent> events = new ArrayList<>();

        // -- Booking ---------------------------------------------------
        events.add(new TimelineEvent(a.getCreatedAt(), Category.BOOKING,
                "Booking created", "Reference " + a.getReference(), a.getCreatedBy()));

        for (AppointmentStatusHistory h : statusHistoryRepository
                .findAllByAppointmentIdOrderByChangedAtAsc(a.getId())) {
            events.add(new TimelineEvent(h.getChangedAt(), Category.BOOKING,
                    statusTitle(h.getFromStatus(), h.getToStatus()),
                    h.getReason(), h.getChangedBy()));
        }

        // -- Team ------------------------------------------------------
        for (AppointmentTeamEvent t : teamEventRepository
                .findAllByAppointmentIdOrderByChangedAtDescIdDesc(a.getId())) {
            events.add(new TimelineEvent(t.getChangedAt(), Category.TEAM,
                    teamTitle(t), t.getReason(), t.getChangedBy()));
        }

        // -- Session ---------------------------------------------------
        Optional<Consultation> consultation = consultationRepository.findByAppointmentId(a.getId());
        consultation.ifPresent(c -> {
            if (c.getStartedAt() != null) {
                events.add(new TimelineEvent(c.getStartedAt(), Category.SESSION,
                        "Session started", c.getModality() == null ? null : "Mode: " + humanise(c.getModality().name()),
                        null));
            }
            for (AttendanceEvent e : attendanceRepository.findAllByConsultationIdOrderByOccurredAtAsc(c.getId())) {
                events.add(new TimelineEvent(e.getOccurredAt(), Category.SESSION,
                        attendanceTitle(e), e.getDetails(), null));
            }
            if (c.getEndedAt() != null) {
                events.add(new TimelineEvent(c.getEndedAt(), Category.SESSION,
                        "Session ended",
                        c.getOutcome() == null ? null : "Outcome: " + humanise(c.getOutcome().name()),
                        null));
            }
            for (ConsultationNote n : noteRepository.findAllByConsultationIdOrderByVersionAsc(c.getId())) {
                if (Boolean.TRUE.equals(n.getIsSigned()) && n.getSignedAt() != null) {
                    boolean amendment = n.getVersion() != null && n.getVersion() > 1;
                    events.add(new TimelineEvent(n.getSignedAt(), Category.CLINICAL,
                            amendment ? "Clinical note amended (version " + n.getVersion() + ")"
                                    : "Clinical note signed",
                            amendment ? n.getAmendmentReason() : null,
                            n.getSignedBy()));
                }
            }
        });

        // -- Bundle, outputs, reviews, follow-ups ----------------------
        Optional<ReleaseBundle> bundle = bundleRepository.findByAppointmentId(a.getId());
        List<ReviewDigest> digests = new ArrayList<>();
        List<FollowUp> followUps = new ArrayList<>();

        bundle.ifPresent(b -> {
            for (Prescription p : prescriptionRepository.findAllByBundleId(b.getId())) {
                events.add(new TimelineEvent(p.getCreatedAt(), Category.CLINICAL,
                        "Prescription " + p.getIssueNumber() + " issued",
                        "Now " + humanise(p.getStatus().name()), p.getCreatedBy()));
            }
            for (Investigation i : investigationRepository.findAllByBundleId(b.getId())) {
                events.add(new TimelineEvent(i.getCreatedAt(), Category.CLINICAL,
                        "Investigation " + i.getIssueNumber() + " requested",
                        "Now " + humanise(i.getStatus().name()), i.getCreatedBy()));
            }

            for (ProfessionalReview r : reviewRepository.findAllByBundleId(b.getId())) {
                String who = name(r.getReviewer());
                String kind = r.getReviewType() == ReviewType.PHARMACY ? "Pharmacy" : "Laboratory";
                if (r.getAssignedAt() != null) {
                    events.add(new TimelineEvent(r.getAssignedAt(), Category.REVIEW,
                            kind + " review assigned", who == null ? "No reviewer yet" : "To " + who, null));
                }
                if (r.getOpenedAt() != null) {
                    events.add(new TimelineEvent(r.getOpenedAt(), Category.REVIEW,
                            kind + " review opened", null, who));
                }
                if (r.getSubmittedAt() != null) {
                    events.add(new TimelineEvent(r.getSubmittedAt(), Category.REVIEW,
                            kind + " review submitted",
                            r.getOutcome() == null ? null : humanise(r.getOutcome().name()),
                            who));
                }
                if (r.getSubmittedToHubAt() != null) {
                    events.add(new TimelineEvent(r.getSubmittedToHubAt(), Category.REVIEW,
                            kind + " query sent to the hub", r.getQueryDetail(), who));
                }
                digests.add(new ReviewDigest(b.getId(), r.getReviewType(),
                        r.getReviewer() == null ? null : r.getReviewer().getId(),
                        r.getOpenedAt(), r.getSubmittedAt(), r.getQueryRaised()));
            }

            for (FollowUp f : followUpRepository.findAllByBundleId(b.getId())) {
                followUps.add(f);
                events.add(new TimelineEvent(f.getCreatedAt(), Category.FOLLOW_UP,
                        "Follow-up recorded", "Now " + humanise(f.getStatus().name()), f.getCreatedBy()));
            }

            if (b.getReleasedAt() != null) {
                events.add(new TimelineEvent(b.getReleasedAt(), Category.RELEASE,
                        "Released to the patient", b.getReleaseNotes(), b.getReleasedBy()));
            } else if (b.getStatus() != null && "BLOCKED".equals(b.getStatus().name())) {
                events.add(new TimelineEvent(b.getUpdatedAt(), Category.RELEASE,
                        "Release held by the hub", b.getBlockedReason(), b.getUpdatedBy()));
            }
        });

        // -- Hub edits, one event per save -----------------------------
        Map<String, List<ClinicalEditRevision>> groups = new LinkedHashMap<>();
        for (ClinicalEditRevision r : editRepository.findAllByAppointmentIdOrderByCreatedAtDescIdAsc(a.getId())) {
            groups.computeIfAbsent(r.getEditGroup(), k -> new ArrayList<>()).add(r);
        }
        for (List<ClinicalEditRevision> group : groups.values()) {
            ClinicalEditRevision head = group.get(0);
            String target = head.getTargetType() == ClinicalEditRevision.Target.REVIEW ? "review" : "follow-up";
            String fields = group.stream().map(ClinicalEditRevision::getFieldName).collect(Collectors.joining(", "));
            events.add(new TimelineEvent(head.getCreatedAt(), Category.EDIT,
                    "Hub edited " + target + " (" + fields + ")", head.getReason(),
                    head.getEditedByName() != null ? head.getEditedByName() : head.getEditedBy()));
        }

        events.removeIf(e -> e.at() == null);
        events.sort(Comparator.comparing(TimelineEvent::at));

        LocalDateTime started = consultation.map(Consultation::getStartedAt).orElse(null);
        LocalDateTime ended = consultation.map(Consultation::getEndedAt).orElse(null);
        LocalDateTime released = bundle.map(ReleaseBundle::getReleasedAt).orElse(null);

        return new Timeline(
                a.getPublicId(),
                a.getReference(),
                patientName(a),
                a.getAppointmentDate(),
                a.getStatus().name(),
                WorkflowStage.of(a, bundle.orElse(null), digests, followUps),
                team(a),
                new Durations(
                        minutes(a.getCreatedAt(), a.getApprovedAt()),
                        minutes(a.getApprovedAt(), started),
                        minutes(started, ended),
                        minutes(ended, released)),
                events);
    }

    // =================================================================
    // Shared
    // =================================================================

    private static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Give both a start and an end date.");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The end date is before the start date.");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new IllegalArgumentException(
                    "Choose a window of " + MAX_WINDOW_DAYS + " days or less.");
        }
    }

    private static Long minutes(LocalDateTime from, LocalDateTime to) {
        return from == null || to == null || to.isBefore(from)
                ? null : Duration.between(from, to).toMinutes();
    }

    private static String name(Users user) {
        return user == null ? null : user.getFullName();
    }

    private static String patientName(Appointment a) {
        if (a.getPatient() == null) {
            return null;
        }
        return (a.getPatient().getFirstName() + " " + a.getPatient().getLastName()).trim();
    }

    private static String statusTitle(String from, String to) {
        String target = humanise(to);
        return from == null ? "Status: " + target : humanise(from) + " to " + target;
    }

    private static String teamTitle(AppointmentTeamEvent t) {
        String role = switch (t.getTeamRole()) {
            case DOCTOR -> "Doctor";
            case NURSE -> "Nurse";
            case PHARMACIST -> "Pharmacist";
            case LABORATORY -> "Laboratory technician";
            case HIM -> "HIM officer";
            case ROOM -> "Room";
        };
        if (t.getFromLabel() == null) {
            return role + " assigned: " + t.getToLabel();
        }
        if (t.getToLabel() == null) {
            return role + " removed: " + t.getFromLabel();
        }
        return role + " changed: " + t.getFromLabel() + " to " + t.getToLabel();
    }

    private static String attendanceTitle(AttendanceEvent e) {
        String who = e.getParticipantRole() == null ? "Someone" : humanise(e.getParticipantRole().name());
        return switch (e.getEventType()) {
            case JOINED -> who + " joined";
            case LEFT -> who + " left";
            case RECONNECTED -> who + " reconnected";
            case MODALITY_CHANGED -> "Switched to " + (e.getModality() == null ? "another mode" : humanise(e.getModality()));
            case IDENTITY_CONFIRMED -> "Patient identity confirmed";
            case WARNING_SENT -> "Time warning sent";
            case TERMINATED -> "Session terminated";
            case ROOM_CLOSED -> "Room closed";
        };
    }

    /** APPROVED_FOR_X to "Approved for x". */
    static String humanise(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String lower = code.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
