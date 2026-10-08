package com.fnph.telepsychiatric.staff;

import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.common.HospitalClock;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Work history and dashboard numbers for one member of the clinical team:
 * doctor, nurse, pharmacist, laboratory technician or HIM officer.
 *
 * <h2>Whose work</h2>
 * Doctors by the consultations they held (consultations.doctor_id), reviewers
 * by the reviews assigned to them (professional_reviews.reviewer_id), nurses
 * by the appointments they prepared (appointments.nurse_id) and the readings
 * they verified or entered from a patient's photo, HIM
 * officers by the appointments they prepared records for
 * (appointments.him_officer_id) and the enrolment checks they resolved. FNPH
 * pathway only: centre consultations belong to centre staff.
 *
 * <h2>Counted when it happened</h2>
 * A review submitted on Tuesday counts on Tuesday, whatever day the
 * consultation was. Lists show everything that happened in the window plus,
 * for reviewers, anything still waiting.
 *
 * <h2>Day boundaries</h2>
 * Stored times are UTC; hospital days are WAT, UTC+1 with no daylight saving,
 * so daily grouping adds an hour before taking the date.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffWorkService {

    public static final int MAX_WINDOW_DAYS = 366;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String STAFFED = "('APPROVED','IN_PROGRESS','COMPLETED','NO_SHOW')";
    private static final String DEAD_DOCS = "('SUPERSEDED','REVOKED','EXPIRED')";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @PersistenceContext
    private EntityManager em;

    /** A team role with work history, and the account role code that grants it. */
    public enum Role {
        DOCTOR("DOCTOR"),
        NURSE("NURSING"),
        PHARMACIST("PHARMACIST"),
        LABORATORY("LABORATORY_TECHNICIAN"),
        HIM("HIM");

        public final String roleCode;

        Role(String roleCode) {
            this.roleCode = roleCode;
        }

        static Role ofRoleCode(String code) {
            return Arrays.stream(values()).filter(r -> r.roleCode.equals(code)).findFirst().orElse(null);
        }
    }

    // -----------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------

    /**
     * One number on the dashboard. {@code format} is count, hours, minutes or
     * percent. {@code tone} is alarm or warn when the number needs action.
     * {@code since} is set when the number has an age worth showing.
     */
    public record Metric(String key, String label, Number value, String format, String tone,
                         String hint, LocalDateTime since) {
        static Metric count(String key, String label, long value) {
            return new Metric(key, label, value, "count", null, null, null);
        }

        Metric warnIfAny(String tone) {
            return value != null && value.doubleValue() > 0
                    ? new Metric(key, label, value, format, tone, hint, since) : this;
        }

        Metric withHint(String text) {
            return new Metric(key, label, value, format, tone, text, since);
        }

        Metric withSince(LocalDateTime at) {
            return new Metric(key, label, value, format, tone, hint, at);
        }
    }

    public record Measure(String key, String label, String noun) {
    }

    public record Day(LocalDate date, Map<String, Long> values) {
    }

    public record Person(String publicId, String name) {
    }

    public record Summary(String role, List<String> roles, Person person, LocalDate from, LocalDate to,
                          List<Metric> now, List<Metric> period, List<Measure> measures,
                          List<Day> daily) {
    }

    /** A doctor's consultation. */
    public record ConsultationItem(String kind, String appointmentPublicId, String reference,
                                   LocalDateTime appointmentDate, String patientName, String ehrNumber,
                                   String status, String consultationPublicId,
                                   LocalDateTime startedAt, LocalDateTime endedAt, Long minutes,
                                   String outcome, boolean noteSigned, LocalDateTime noteSignedAt,
                                   long prescriptions, long investigations, boolean followUp,
                                   String bundleStatus) {
    }

    /** One pharmacy or laboratory review. */
    public record ReviewItem(String kind, String reviewPublicId, String reviewType, String documentNumber,
                             String documentStatus, String appointmentPublicId, String reference,
                             LocalDateTime appointmentDate, String patientName, String ehrNumber,
                             LocalDateTime assignedAt, LocalDateTime openedAt, LocalDateTime submittedAt,
                             String outcome, boolean queryRaised, Double turnaroundHours, String state) {
    }

    /** One record retrieval for an HIM officer. */
    /**
     * One appointment prepared by an HIM officer (record retrieval) or a nurse
     * (readings and room). {@code readings} is set for nurses only: how many
     * vitals records the appointment has.
     */
    public record RecordItem(String kind, String appointmentPublicId, String reference,
                             LocalDateTime appointmentDate, String patientName, String ehrNumber,
                             String status, String state, LocalDateTime startedAt,
                             LocalDateTime completedAt, String exceptionReason, Double hoursBeforeSession,
                             Long readings, String room) {
    }

    /** One enrolment check an HIM officer resolved or rejected. */
    public record VerificationItem(String kind, String requestPublicId, String ehrNumberClaimed,
                                   String fullName, String status, LocalDateTime submittedAt,
                                   LocalDateTime resolvedAt, Double hoursToResolve) {
    }

    public record Page(String role, String kind, long total, int page, int size, List<?> items) {
    }

    /** One line of the Hub Coordinator's staff list. */
    public record StaffRow(String publicId, String name, String role, long done, String doneLabel,
                           long waiting, String waitingLabel) {
    }

    // -----------------------------------------------------------------
    // Who
    // -----------------------------------------------------------------

    /** The roles with work history this person holds, in display order. */
    public List<Role> rolesOf(Users user) {
        if (user.getCentre() != null) {
            return List.of();
        }
        List<String> codes = userRoleRepository.findRoleCodesByUserId(user.getId());
        return Arrays.stream(Role.values()).filter(r -> codes.contains(r.roleCode)).toList();
    }

    public Users requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("No such staff member"));
    }

    public Users requireUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such staff member"));
    }

    /** The role asked for, or the person's first, refusing one they do not hold. */
    public Role resolveRole(Users user, String requested) {
        List<Role> held = rolesOf(user);
        if (held.isEmpty()) {
            throw new EntityNotFoundException("No work history for this account. It covers doctors, "
                    + "nurses, pharmacists, laboratory technicians and HIM officers.");
        }
        if (requested == null || requested.isBlank()) {
            return held.get(0);
        }
        Role role;
        try {
            role = Role.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Role must be DOCTOR, NURSE, PHARMACIST, LABORATORY or HIM");
        }
        if (!held.contains(role)) {
            throw new IllegalArgumentException("This person does not hold the " + role.name() + " role");
        }
        return role;
    }

    // -----------------------------------------------------------------
    // Summary
    // -----------------------------------------------------------------

    public Summary summary(Users user, Role role, LocalDate from, LocalDate to) {
        checkWindow(from, to);
        Window w = new Window(from, to);
        List<String> roles = rolesOf(user).stream().map(Role::name).toList();
        Person person = new Person(user.getPublicId(), user.getFullName());
        return switch (role) {
            case DOCTOR -> doctor(user, roles, person, w);
            case NURSE -> nurse(user, roles, person, w);
            case PHARMACIST -> reviewer(user, "PHARMACY", role, roles, person, w);
            case LABORATORY -> reviewer(user, "LABORATORY", role, roles, person, w);
            case HIM -> him(user, roles, person, w);
        };
    }

    private Summary doctor(Users user, List<String> roles, Person person, Window w) {
        Map<String, Object> u = Map.of("u", user.getId());
        Map<String, Object> uw = w.params(u);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = HospitalClock.today();

        List<Metric> nowMetrics = List.of(
                Metric.count("notesToSign", "Notes to sign", count("""
                        SELECT COUNT(*) FROM consultations c
                        WHERE c.doctor_id = :u AND c.started_at IS NOT NULL AND c.ended_at IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM consultation_notes n
                                          WHERE n.consultation_id = c.id AND n.is_signed = 1)
                        """, u)).warnIfAny("alarm")
                        .withHint("Sessions ended without a signed note. Nothing can be released until it is."),
                Metric.count("today", "Consultations today", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.doctor_id = :u AND a.status IN %s
                          AND a.appointment_date >= :ds AND a.appointment_date < :de
                        """.formatted(STAFFED), with(u, "ds", HospitalClock.toUtc(today, LocalTime.MIDNIGHT),
                        "de", HospitalClock.toUtc(today.plusDays(1), LocalTime.MIDNIGHT)))),
                Metric.count("upcoming", "Upcoming", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.doctor_id = :u AND a.status = 'APPROVED' AND a.appointment_date >= :now
                        """, with(u, "now", now))));

        long held = count("SELECT COUNT(*) FROM consultations c WHERE c.doctor_id = :u AND c.started_at >= :f AND c.started_at < :t", uw);
        long completed = count("SELECT COUNT(*) FROM consultations c WHERE c.doctor_id = :u AND c.started_at >= :f AND c.started_at < :t AND c.outcome = 'COMPLETED'", uw);
        long endedEarly = count("SELECT COUNT(*) FROM consultations c WHERE c.doctor_id = :u AND c.started_at >= :f AND c.started_at < :t AND c.outcome = 'TERMINATED_EARLY'", uw);
        long noShows = count("SELECT COUNT(*) FROM appointments a WHERE a.doctor_id = :u AND a.status = 'NO_SHOW' AND a.appointment_date >= :f AND a.appointment_date < :t", uw);

        List<Metric> period = List.of(
                Metric.count("sessions", "Sessions held", held),
                Metric.count("completed", "Completed", completed),
                Metric.count("endedEarly", "Ended early", endedEarly),
                Metric.count("noShows", "Patient did not attend", noShows),
                avg("avgSession", "Average session", "minutes", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, c.started_at, c.ended_at)) / 60 FROM consultations c
                        WHERE c.doctor_id = :u AND c.started_at >= :f AND c.started_at < :t AND c.ended_at IS NOT NULL
                        """, uw),
                Metric.count("notesSigned", "Notes signed", count("""
                        SELECT COUNT(*) FROM consultation_notes n JOIN consultations c ON c.id = n.consultation_id
                        WHERE c.doctor_id = :u AND n.is_signed = 1 AND n.version = 1
                          AND n.signed_at >= :f AND n.signed_at < :t
                        """, uw)),
                Metric.count("amendments", "Notes amended", count("""
                        SELECT COUNT(*) FROM consultation_notes n JOIN consultations c ON c.id = n.consultation_id
                        WHERE c.doctor_id = :u AND n.version > 1 AND n.created_at >= :f AND n.created_at < :t
                        """, uw)),
                avg("avgToSign", "Session end to signed note", "hours", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, c.ended_at, n.signed_at)) / 3600
                        FROM consultation_notes n JOIN consultations c ON c.id = n.consultation_id
                        WHERE c.doctor_id = :u AND n.is_signed = 1 AND n.version = 1 AND c.ended_at IS NOT NULL
                          AND n.signed_at >= :f AND n.signed_at < :t
                        """, uw),
                Metric.count("prescriptions", "Prescriptions issued", count("""
                        SELECT COUNT(*) FROM prescriptions p
                        WHERE p.doctor_id = :u AND p.consultation_id IS NOT NULL AND p.not_required = 0
                          AND p.created_at >= :f AND p.created_at < :t
                        """, uw)),
                Metric.count("investigations", "Investigations requested", count("""
                        SELECT COUNT(*) FROM investigations i
                        WHERE i.doctor_id = :u AND i.consultation_id IS NOT NULL AND i.not_required = 0
                          AND i.created_at >= :f AND i.created_at < :t
                        """, uw)),
                Metric.count("corrections", "Prescriptions replaced", count("""
                        SELECT COUNT(*) FROM prescriptions p
                        WHERE p.doctor_id = :u AND p.supersedes_id IS NOT NULL
                          AND p.created_at >= :f AND p.created_at < :t
                        """, uw)),
                Metric.count("followUps", "Follow-ups recommended", count("""
                        SELECT COUNT(*) FROM follow_ups fu JOIN consultations c ON c.id = fu.consultation_id
                        WHERE c.doctor_id = :u AND fu.status <> 'NOT_REQUIRED'
                          AND fu.created_at >= :f AND fu.created_at < :t
                        """, uw)));

        List<Measure> measures = List.of(
                new Measure("sessions", "Sessions", "sessions held"),
                new Measure("prescriptions", "Prescriptions", "prescriptions issued"),
                new Measure("investigations", "Investigations", "investigations requested"));
        Map<String, Map<LocalDate, Long>> series = new LinkedHashMap<>();
        series.put("sessions", daily("c.started_at", "consultations c WHERE c.doctor_id = :u", uw));
        series.put("prescriptions", daily("p.created_at",
                "prescriptions p WHERE p.doctor_id = :u AND p.consultation_id IS NOT NULL AND p.not_required = 0", uw));
        series.put("investigations", daily("i.created_at",
                "investigations i WHERE i.doctor_id = :u AND i.consultation_id IS NOT NULL AND i.not_required = 0", uw));

        return new Summary(Role.DOCTOR.name(), roles, person, w.from, w.to, nowMetrics, period, measures,
                days(w, series));
    }

    private Summary reviewer(Users user, String type, Role role, List<String> roles, Person person, Window w) {
        Map<String, Object> ut = Map.of("u", user.getId(), "type", type);
        Map<String, Object> utw = w.params(ut);
        String teamColumn = role == Role.PHARMACIST ? "pharmacist_id" : "laboratory_technician_id";

        String waitingSql = """
                FROM professional_reviews r
                LEFT JOIN prescriptions p ON p.id = r.prescription_id
                LEFT JOIN investigations i ON i.id = r.investigation_id
                WHERE r.reviewer_id = :u AND r.review_type = :type AND r.submitted_at IS NULL
                  AND COALESCE(p.status, i.status) NOT IN %s
                """.formatted(DEAD_DOCS);
        long waiting = count("SELECT COUNT(*) " + waitingSql, ut);
        long notOpened = count("SELECT COUNT(*) " + waitingSql + " AND r.opened_at IS NULL", ut);
        LocalDateTime oldest = dateTime(single("SELECT MIN(r.assigned_at) " + waitingSql, ut));

        List<Metric> nowMetrics = List.of(
                Metric.count("waiting", "Waiting for you", waiting).warnIfAny("warn").withSince(oldest),
                Metric.count("notOpened", "Not opened yet", notOpened).warnIfAny("warn"));

        long submitted = count("SELECT COUNT(*) FROM professional_reviews r WHERE r.reviewer_id = :u AND r.review_type = :type AND r.submitted_at >= :f AND r.submitted_at < :t", utw);
        long verified = count("SELECT COUNT(*) FROM professional_reviews r WHERE r.reviewer_id = :u AND r.review_type = :type AND r.submitted_at >= :f AND r.submitted_at < :t AND r.outcome = 'VERIFIED'", utw);
        long queries = count("SELECT COUNT(*) FROM professional_reviews r WHERE r.reviewer_id = :u AND r.review_type = :type AND r.submitted_at >= :f AND r.submitted_at < :t AND r.query_raised = 1", utw);

        List<Metric> period = List.of(
                Metric.count("assigned", "Assigned to you", count("""
                        SELECT COUNT(*) FROM professional_reviews r
                        WHERE r.reviewer_id = :u AND r.review_type = :type AND r.assigned_at >= :f AND r.assigned_at < :t
                        """, utw)),
                Metric.count("submitted", "Submitted", submitted),
                Metric.count("verified", "Verified", verified),
                Metric.count("queries", "Queries raised", queries),
                percent("verifiedRate", "Verified without a query", verified, submitted),
                avg("avgTurnaround", "Assigned to submitted", "hours", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, r.assigned_at, r.submitted_at)) / 3600 FROM professional_reviews r
                        WHERE r.reviewer_id = :u AND r.review_type = :type AND r.submitted_at >= :f AND r.submitted_at < :t
                        """, utw),
                avg("avgToOpen", "Assigned to opened", "hours", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, r.assigned_at, r.opened_at)) / 3600 FROM professional_reviews r
                        WHERE r.reviewer_id = :u AND r.review_type = :type AND r.opened_at IS NOT NULL
                          AND r.assigned_at >= :f AND r.assigned_at < :t
                        """, utw),
                Metric.count("consultations", "Consultations on your team", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.%s = :u AND a.status IN %s AND a.appointment_date >= :f AND a.appointment_date < :t
                        """.formatted(teamColumn, STAFFED), w.params(Map.of("u", user.getId())))));

        List<Measure> measures = List.of(
                new Measure("assigned", "Assigned", "reviews assigned"),
                new Measure("submitted", "Submitted", "reviews submitted"));
        Map<String, Map<LocalDate, Long>> series = new LinkedHashMap<>();
        series.put("assigned", daily("r.assigned_at", "professional_reviews r WHERE r.reviewer_id = :u AND r.review_type = :type", utw));
        series.put("submitted", daily("r.submitted_at", "professional_reviews r WHERE r.reviewer_id = :u AND r.review_type = :type", utw));

        return new Summary(role.name(), roles, person, w.from, w.to, nowMetrics, period, measures, days(w, series));
    }

    private Summary nurse(Users user, List<String> roles, Person person, Window w) {
        Map<String, Object> u = Map.of("u", user.getId());
        Map<String, Object> uw = w.params(u);
        Map<String, Object> nw = w.params(Map.of("name", user.getUsername()));
        LocalDate today = HospitalClock.today();

        String openSql = """
                FROM appointments a
                WHERE a.nurse_id = :u AND a.status = 'APPROVED' AND a.nursing_completed_at IS NULL
                """;
        String noReadings = " AND NOT EXISTS (SELECT 1 FROM vitals vt WHERE vt.appointment_id = a.id)";
        LocalDateTime next = dateTime(single("SELECT MIN(a.appointment_date) " + openSql, u));
        List<Metric> nowMetrics = List.of(
                Metric.count("toPrepare", "Patients to prepare", count("SELECT COUNT(*) " + openSql, u))
                        .warnIfAny("warn").withSince(next),
                Metric.count("dueToday", "Needed today", count("SELECT COUNT(*) " + openSql
                                        + " AND a.appointment_date >= :ds AND a.appointment_date < :de",
                                with(u, "ds", HospitalClock.toUtc(today, LocalTime.MIDNIGHT),
                                        "de", HospitalClock.toUtc(today.plusDays(1), LocalTime.MIDNIGHT))))
                        .warnIfAny("alarm"),
                Metric.count("readingsAsFile", "Readings sent as a photo", count("SELECT COUNT(*) " + openSql + noReadings
                        + """
                         AND EXISTS (SELECT 1 FROM file_uploads fl WHERE fl.reference_id = a.public_id
                                     AND fl.category = 'VITALS_EVIDENCE' AND fl.deleted = 0)
                        """, u)).warnIfAny("warn").withHint("Enter them from the photo in your queue"),
                Metric.count("readingsMissing", "No readings at all", count("SELECT COUNT(*) " + openSql + noReadings
                        + """
                         AND NOT EXISTS (SELECT 1 FROM file_uploads fl WHERE fl.reference_id = a.public_id
                                         AND fl.category = 'VITALS_EVIDENCE' AND fl.deleted = 0)
                        """, u)).warnIfAny("alarm").withHint("Contact the patient or raise an issue"));

        long prepared = count("SELECT COUNT(*) FROM appointments a WHERE a.nurse_id = :u AND a.nursing_completed_at >= :f AND a.nursing_completed_at < :t", uw);
        long inTime = count("SELECT COUNT(*) FROM appointments a WHERE a.nurse_id = :u AND a.nursing_completed_at >= :f AND a.nursing_completed_at < :t AND a.nursing_completed_at <= a.appointment_date", uw);

        List<Metric> period = List.of(
                Metric.count("assigned", "Consultations assigned", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.nurse_id = :u AND a.status IN %s AND a.appointment_date >= :f AND a.appointment_date < :t
                        """.formatted(STAFFED), uw)),
                Metric.count("prepared", "Patients prepared", prepared),
                percent("onTime", "Ready before the session", inTime, prepared),
                avg("avgAhead", "Ready ahead of the session", "hours", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, a.nursing_completed_at, a.appointment_date)) / 3600 FROM appointments a
                        WHERE a.nurse_id = :u AND a.nursing_completed_at >= :f AND a.nursing_completed_at < :t
                        """, uw),
                avg("avgPrepare", "Started to ready", "minutes", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, a.nursing_started_at, a.nursing_completed_at)) / 60 FROM appointments a
                        WHERE a.nurse_id = :u AND a.nursing_started_at IS NOT NULL
                          AND a.nursing_completed_at >= :f AND a.nursing_completed_at < :t
                        """, uw),
                Metric.count("readingsVerified", "Readings verified", count("""
                        SELECT COUNT(*) FROM vitals vt
                        WHERE vt.verified_by = :name AND vt.verified_at >= :f AND vt.verified_at < :t
                        """, nw)),
                Metric.count("readingsTranscribed", "Entered from a photo", count("""
                        SELECT COUNT(*) FROM vitals vt
                        WHERE vt.verified_by = :name AND vt.notes LIKE 'Transcribed by %'
                          AND vt.verified_at >= :f AND vt.verified_at < :t
                        """, nw)),
                Metric.count("issues", "Issues raised", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.nurse_id = :u AND a.nursing_exception_at >= :f AND a.nursing_exception_at < :t
                        """, uw)));

        List<Measure> measures = List.of(
                new Measure("prepared", "Patients", "patients prepared"),
                new Measure("readings", "Readings", "readings verified"));
        Map<String, Map<LocalDate, Long>> series = new LinkedHashMap<>();
        series.put("prepared", daily("a.nursing_completed_at", "appointments a WHERE a.nurse_id = :u", uw));
        series.put("readings", daily("vt.verified_at", "vitals vt WHERE vt.verified_by = :name", nw));

        return new Summary(Role.NURSE.name(), roles, person, w.from, w.to, nowMetrics, period, measures, days(w, series));
    }

    private Summary him(Users user, List<String> roles, Person person, Window w) {
        Map<String, Object> u = Map.of("u", user.getId());
        Map<String, Object> uw = w.params(u);
        Map<String, Object> nw = w.params(Map.of("name", user.getUsername()));
        LocalDate today = HospitalClock.today();

        String openSql = """
                FROM appointments a
                WHERE a.him_officer_id = :u AND a.status = 'APPROVED' AND a.him_completed_at IS NULL
                """;
        LocalDateTime oldest = dateTime(single("SELECT MIN(a.appointment_date) " + openSql, u));
        List<Metric> nowMetrics = List.of(
                Metric.count("toPrepare", "Records to prepare", count("SELECT COUNT(*) " + openSql, u))
                        .warnIfAny("warn").withSince(oldest),
                Metric.count("dueToday", "Needed today", count("SELECT COUNT(*) " + openSql
                                        + " AND a.appointment_date >= :ds AND a.appointment_date < :de",
                                with(u, "ds", HospitalClock.toUtc(today, LocalTime.MIDNIGHT),
                                        "de", HospitalClock.toUtc(today.plusDays(1), LocalTime.MIDNIGHT))))
                        .warnIfAny("alarm"),
                Metric.count("issuesOpen", "Issues raised, not resolved", count("SELECT COUNT(*) " + openSql
                        + " AND a.him_exception_at IS NOT NULL", u)).warnIfAny("warn"),
                Metric.count("checksWaiting", "Enrolment checks waiting", count("""
                        SELECT COUNT(*) FROM patient_verification_requests v
                        WHERE v.status IN ('SUBMITTED','WITH_HIM')
                        """, Map.of())).withHint("Shared by every HIM officer"));

        long prepared = count("SELECT COUNT(*) FROM appointments a WHERE a.him_officer_id = :u AND a.him_completed_at >= :f AND a.him_completed_at < :t", uw);
        long inTime = count("SELECT COUNT(*) FROM appointments a WHERE a.him_officer_id = :u AND a.him_completed_at >= :f AND a.him_completed_at < :t AND a.him_completed_at <= a.appointment_date", uw);

        List<Metric> period = List.of(
                Metric.count("assigned", "Consultations assigned", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.him_officer_id = :u AND a.status IN %s AND a.appointment_date >= :f AND a.appointment_date < :t
                        """.formatted(STAFFED), uw)),
                Metric.count("prepared", "Records prepared", prepared),
                percent("onTime", "Ready before the session", inTime, prepared),
                avg("avgAhead", "Ready ahead of the session", "hours", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, a.him_completed_at, a.appointment_date)) / 3600 FROM appointments a
                        WHERE a.him_officer_id = :u AND a.him_completed_at >= :f AND a.him_completed_at < :t
                        """, uw),
                avg("avgPrepare", "Started to ready", "minutes", """
                        SELECT AVG(TIMESTAMPDIFF(SECOND, a.him_started_at, a.him_completed_at)) / 60 FROM appointments a
                        WHERE a.him_officer_id = :u AND a.him_started_at IS NOT NULL
                          AND a.him_completed_at >= :f AND a.him_completed_at < :t
                        """, uw),
                Metric.count("issues", "Issues raised", count("""
                        SELECT COUNT(*) FROM appointments a
                        WHERE a.him_officer_id = :u AND a.him_exception_at >= :f AND a.him_exception_at < :t
                        """, uw)),
                Metric.count("checksResolved", "Enrolment checks resolved", count("""
                        SELECT COUNT(*) FROM patient_verification_requests v
                        WHERE v.resolved_by = :name AND v.status = 'RESOLVED' AND v.resolved_at >= :f AND v.resolved_at < :t
                        """, nw)),
                Metric.count("checksRejected", "Enrolment checks rejected", count("""
                        SELECT COUNT(*) FROM patient_verification_requests v
                        WHERE v.resolved_by = :name AND v.status = 'REJECTED' AND v.resolved_at >= :f AND v.resolved_at < :t
                        """, nw)));

        List<Measure> measures = List.of(
                new Measure("prepared", "Records", "records prepared"),
                new Measure("checks", "Enrolment checks", "enrolment checks decided"));
        Map<String, Map<LocalDate, Long>> series = new LinkedHashMap<>();
        series.put("prepared", daily("a.him_completed_at", "appointments a WHERE a.him_officer_id = :u", uw));
        series.put("checks", daily("v.resolved_at",
                "patient_verification_requests v WHERE v.resolved_by = :name AND v.status IN ('RESOLVED','REJECTED')", nw));

        return new Summary(Role.HIM.name(), roles, person, w.from, w.to, nowMetrics, period, measures, days(w, series));
    }

    // -----------------------------------------------------------------
    // Lists
    // -----------------------------------------------------------------

    /**
     * The history behind the numbers, newest first. {@code kind} only matters
     * for HIM: RECORDS (default) or VERIFICATIONS. {@code q} matches patient
     * name, EHR number or reference.
     */
    public Page items(Users user, Role role, LocalDate from, LocalDate to, String kind, String q,
                      int page, int size) {
        checkWindow(from, to);
        Window w = new Window(from, to);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        int safePage = Math.max(0, page);
        String term = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return switch (role) {
            case DOCTOR -> consultations(user, w, term, safePage, safeSize);
            case NURSE -> preparations(user, w, term, safePage, safeSize);
            case PHARMACIST -> reviews(user, "PHARMACY", role, w, term, safePage, safeSize);
            case LABORATORY -> reviews(user, "LABORATORY", role, w, term, safePage, safeSize);
            case HIM -> "VERIFICATIONS".equalsIgnoreCase(kind)
                    ? verifications(user, w, term, safePage, safeSize)
                    : records(user, w, term, safePage, safeSize);
        };
    }

    /** Search by patient name, EHR number or reference. Omitted when there is no search. */
    private static String patientMatch(String q) {
        return q == null ? "" : """
                 AND (LOWER(CONCAT(pt.first_name, ' ', pt.last_name)) LIKE :q
                      OR LOWER(pt.ehr_number) LIKE :q OR LOWER(a.reference) LIKE :q)
                """;
    }

    private Page consultations(Users user, Window w, String q, int page, int size) {
        String from = """
                FROM appointments a
                JOIN patients pt ON pt.id = a.patient_id
                LEFT JOIN consultations c ON c.appointment_id = a.id
                WHERE a.doctor_id = :u AND a.status IN %s
                  AND a.appointment_date >= :f AND a.appointment_date < :t
                """.formatted(STAFFED) + patientMatch(q);
        Map<String, Object> p = w.params(with(Map.of("u", user.getId()), "q", q));
        long total = count("SELECT COUNT(*) " + from, p);

        List<Object[]> rows = rows("""
                SELECT a.public_id, a.reference, a.appointment_date, CONCAT(pt.first_name, ' ', pt.last_name),
                       pt.ehr_number, a.status, c.public_id, c.started_at, c.ended_at, c.outcome,
                       (SELECT MIN(n.signed_at) FROM consultation_notes n WHERE n.consultation_id = c.id AND n.is_signed = 1),
                       (SELECT COUNT(*) FROM prescriptions rx WHERE rx.consultation_id = c.id AND rx.not_required = 0),
                       (SELECT COUNT(*) FROM investigations ix WHERE ix.consultation_id = c.id AND ix.not_required = 0),
                       (SELECT COUNT(*) FROM follow_ups fu WHERE fu.consultation_id = c.id AND fu.status <> 'NOT_REQUIRED'),
                       (SELECT rb.status FROM release_bundles rb WHERE rb.appointment_id = a.id LIMIT 1)
                """ + from + " ORDER BY a.appointment_date DESC LIMIT :lim OFFSET :off",
                paged(p, page, size));

        List<ConsultationItem> items = rows.stream().map(r -> {
            LocalDateTime started = dateTime(r[7]);
            LocalDateTime ended = dateTime(r[8]);
            LocalDateTime signed = dateTime(r[10]);
            Long minutes = started != null && ended != null
                    ? java.time.Duration.between(started, ended).toMinutes() : null;
            return new ConsultationItem("CONSULTATION", str(r[0]), str(r[1]), dateTime(r[2]), str(r[3]),
                    str(r[4]), str(r[5]), str(r[6]), started, ended, minutes, str(r[9]),
                    signed != null, signed, toLong(r[11]), toLong(r[12]), toLong(r[13]) > 0, str(r[14]));
        }).toList();
        return new Page(Role.DOCTOR.name(), "CONSULTATIONS", total, page, size, items);
    }

    private Page reviews(Users user, String type, Role role, Window w, String q, int page, int size) {
        String from = """
                FROM professional_reviews r
                LEFT JOIN prescriptions p ON p.id = r.prescription_id
                LEFT JOIN investigations i ON i.id = r.investigation_id
                LEFT JOIN release_bundles rb ON rb.id = COALESCE(p.bundle_id, i.bundle_id)
                LEFT JOIN appointments a ON a.id = rb.appointment_id
                LEFT JOIN patients pt ON pt.id = COALESCE(p.patient_id, i.patient_id)
                WHERE r.reviewer_id = :u AND r.review_type = :type
                  AND ((r.assigned_at >= :f AND r.assigned_at < :t)
                       OR (r.submitted_at >= :f AND r.submitted_at < :t)
                       OR (r.submitted_at IS NULL AND COALESCE(p.status, i.status) NOT IN %s))
                """.formatted(DEAD_DOCS) + patientMatch(q);
        Map<String, Object> p = w.params(with(Map.of("u", user.getId(), "type", type), "q", q));
        long total = count("SELECT COUNT(*) " + from, p);

        List<Object[]> rows = rows("""
                SELECT r.public_id, r.review_type, COALESCE(p.issue_number, i.issue_number), COALESCE(p.status, i.status),
                       a.public_id, a.reference, a.appointment_date, CONCAT(pt.first_name, ' ', pt.last_name), pt.ehr_number,
                       r.assigned_at, r.opened_at, r.submitted_at, r.outcome, r.query_raised
                """ + from + " ORDER BY (r.submitted_at IS NULL) DESC, COALESCE(r.submitted_at, r.assigned_at) DESC LIMIT :lim OFFSET :off",
                paged(p, page, size));

        List<ReviewItem> items = rows.stream().map(r -> {
            LocalDateTime assigned = dateTime(r[9]);
            LocalDateTime opened = dateTime(r[10]);
            LocalDateTime submitted = dateTime(r[11]);
            boolean query = bool(r[13]);
            String docStatus = str(r[3]);
            String state = submitted != null ? (query ? "QUERY_RAISED" : "SUBMITTED")
                    : docStatus != null && List.of("SUPERSEDED", "REVOKED", "EXPIRED").contains(docStatus) ? "WITHDRAWN"
                    : opened != null ? "IN_PROGRESS" : "NOT_OPENED";
            return new ReviewItem("REVIEW", str(r[0]), str(r[1]), str(r[2]), docStatus, str(r[4]), str(r[5]),
                    dateTime(r[6]), str(r[7]), str(r[8]), assigned, opened, submitted, str(r[12]), query,
                    hoursBetween(assigned, submitted), state);
        }).toList();
        return new Page(role.name(), "REVIEWS", total, page, size, items);
    }

    private Page records(Users user, Window w, String q, int page, int size) {
        String from = """
                FROM appointments a
                JOIN patients pt ON pt.id = a.patient_id
                WHERE a.him_officer_id = :u AND a.status IN %s
                  AND ((a.appointment_date >= :f AND a.appointment_date < :t)
                       OR (a.him_completed_at >= :f AND a.him_completed_at < :t))
                """.formatted(STAFFED) + patientMatch(q);
        Map<String, Object> p = w.params(with(Map.of("u", user.getId()), "q", q));
        long total = count("SELECT COUNT(*) " + from, p);

        List<Object[]> rows = rows("""
                SELECT a.public_id, a.reference, a.appointment_date, CONCAT(pt.first_name, ' ', pt.last_name),
                       pt.ehr_number, a.status, a.him_started_at, a.him_completed_at, a.him_exception_at,
                       a.him_exception_reason
                """ + from + " ORDER BY a.appointment_date DESC LIMIT :lim OFFSET :off", paged(p, page, size));

        List<RecordItem> items = rows.stream().map(r -> {
            LocalDateTime date = dateTime(r[2]);
            LocalDateTime started = dateTime(r[6]);
            LocalDateTime completed = dateTime(r[7]);
            LocalDateTime exception = dateTime(r[8]);
            String state = completed != null ? "TREATED" : exception != null ? "EXCEPTION"
                    : started != null ? "IN_PROGRESS" : "UNTREATED";
            return new RecordItem("RECORD", str(r[0]), str(r[1]), date, str(r[3]), str(r[4]), str(r[5]), state,
                    started, completed, exception != null && completed == null ? str(r[9]) : null,
                    hoursBetween(completed, date), null, null);
        }).toList();
        return new Page(Role.HIM.name(), "RECORDS", total, page, size, items);
    }

    private Page preparations(Users user, Window w, String q, int page, int size) {
        String from = """
                FROM appointments a
                JOIN patients pt ON pt.id = a.patient_id
                WHERE a.nurse_id = :u AND a.status IN %s
                  AND ((a.appointment_date >= :f AND a.appointment_date < :t)
                       OR (a.nursing_completed_at >= :f AND a.nursing_completed_at < :t))
                """.formatted(STAFFED) + patientMatch(q);
        Map<String, Object> p = w.params(with(Map.of("u", user.getId()), "q", q));
        long total = count("SELECT COUNT(*) " + from, p);

        List<Object[]> rows = rows("""
                SELECT a.public_id, a.reference, a.appointment_date, CONCAT(pt.first_name, ' ', pt.last_name),
                       pt.ehr_number, a.status, a.nursing_started_at, a.nursing_completed_at, a.nursing_exception_at,
                       a.nursing_exception_reason,
                       (SELECT COUNT(*) FROM vitals vt WHERE vt.appointment_id = a.id), a.room
                """ + from + " ORDER BY a.appointment_date DESC LIMIT :lim OFFSET :off", paged(p, page, size));

        List<RecordItem> items = rows.stream().map(r -> {
            LocalDateTime date = dateTime(r[2]);
            LocalDateTime started = dateTime(r[6]);
            LocalDateTime completed = dateTime(r[7]);
            LocalDateTime exception = dateTime(r[8]);
            String state = completed != null ? "TREATED" : exception != null ? "EXCEPTION"
                    : started != null ? "IN_PROGRESS" : "UNTREATED";
            return new RecordItem("PREPARATION", str(r[0]), str(r[1]), date, str(r[3]), str(r[4]), str(r[5]),
                    state, started, completed, exception != null && completed == null ? str(r[9]) : null,
                    hoursBetween(completed, date), toLong(r[10]), str(r[11]));
        }).toList();
        return new Page(Role.NURSE.name(), "PREPARATIONS", total, page, size, items);
    }

    private Page verifications(Users user, Window w, String q, int page, int size) {
        String from = """
                FROM patient_verification_requests v
                WHERE v.resolved_by = :name AND v.status IN ('RESOLVED','REJECTED')
                  AND v.resolved_at >= :f AND v.resolved_at < :t
                """ + (q == null ? "" : " AND (LOWER(v.full_name) LIKE :q OR LOWER(v.ehr_number_claimed) LIKE :q)");
        Map<String, Object> p = w.params(with(Map.of("name", user.getUsername()), "q", q));
        long total = count("SELECT COUNT(*) " + from, p);
        List<Object[]> rows = rows("""
                SELECT v.public_id, v.ehr_number_claimed, v.full_name, v.status, v.created_at, v.resolved_at
                """ + from + " ORDER BY v.resolved_at DESC LIMIT :lim OFFSET :off", paged(p, page, size));
        List<VerificationItem> items = rows.stream().map(r -> {
            LocalDateTime created = dateTime(r[4]);
            LocalDateTime resolved = dateTime(r[5]);
            return new VerificationItem("VERIFICATION", str(r[0]), str(r[1]), str(r[2]), str(r[3]), created,
                    resolved, hoursBetween(created, resolved));
        }).toList();
        return new Page(Role.HIM.name(), "VERIFICATIONS", total, page, size, items);
    }

    // -----------------------------------------------------------------
    // Hub Coordinator's staff list
    // -----------------------------------------------------------------

    /** Everyone with work history, one row per role held, with the headline numbers for the window. */
    public List<StaffRow> staff(LocalDate from, LocalDate to) {
        checkWindow(from, to);
        Window w = new Window(from, to);
        Map<String, Object> wp = w.params(Map.of());

        Map<Long, Long> sessions = grouped("SELECT c.doctor_id, COUNT(*) FROM consultations c WHERE c.started_at >= :f AND c.started_at < :t GROUP BY c.doctor_id", wp);
        Map<Long, Long> toSign = grouped("""
                SELECT c.doctor_id, COUNT(*) FROM consultations c
                WHERE c.started_at IS NOT NULL AND c.ended_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM consultation_notes n WHERE n.consultation_id = c.id AND n.is_signed = 1)
                GROUP BY c.doctor_id
                """, Map.of());
        Map<String, Map<Long, Long>> submitted = new HashMap<>();
        Map<String, Map<Long, Long>> waiting = new HashMap<>();
        for (String type : List.of("PHARMACY", "LABORATORY")) {
            submitted.put(type, grouped("""
                    SELECT r.reviewer_id, COUNT(*) FROM professional_reviews r
                    WHERE r.review_type = :type AND r.submitted_at >= :f AND r.submitted_at < :t GROUP BY r.reviewer_id
                    """, with(wp, "type", type)));
            waiting.put(type, grouped("""
                    SELECT r.reviewer_id, COUNT(*) FROM professional_reviews r
                    LEFT JOIN prescriptions p ON p.id = r.prescription_id
                    LEFT JOIN investigations i ON i.id = r.investigation_id
                    WHERE r.review_type = :type AND r.submitted_at IS NULL AND COALESCE(p.status, i.status) NOT IN %s
                    GROUP BY r.reviewer_id
                    """.formatted(DEAD_DOCS), Map.of("type", type)));
        }
        Map<Long, Long> nursed = grouped("SELECT a.nurse_id, COUNT(*) FROM appointments a WHERE a.nursing_completed_at >= :f AND a.nursing_completed_at < :t GROUP BY a.nurse_id", wp);
        Map<Long, Long> toNurse = grouped("SELECT a.nurse_id, COUNT(*) FROM appointments a WHERE a.status = 'APPROVED' AND a.nursing_completed_at IS NULL GROUP BY a.nurse_id", Map.of());
        Map<Long, Long> prepared = grouped("SELECT a.him_officer_id, COUNT(*) FROM appointments a WHERE a.him_completed_at >= :f AND a.him_completed_at < :t GROUP BY a.him_officer_id", wp);
        Map<Long, Long> toPrepare = grouped("SELECT a.him_officer_id, COUNT(*) FROM appointments a WHERE a.status = 'APPROVED' AND a.him_completed_at IS NULL GROUP BY a.him_officer_id", Map.of());

        List<StaffRow> rows = new ArrayList<>();
        for (Role role : Role.values()) {
            for (Users u : userRepository.findActiveByRoleCode(role.roleCode)) {
                if (u.getCentre() != null) {
                    continue;
                }
                Long id = u.getId();
                rows.add(switch (role) {
                    case DOCTOR -> new StaffRow(u.getPublicId(), u.getFullName(), role.name(),
                            sessions.getOrDefault(id, 0L), "sessions held", toSign.getOrDefault(id, 0L), "notes to sign");
                    case NURSE -> new StaffRow(u.getPublicId(), u.getFullName(), role.name(),
                            nursed.getOrDefault(id, 0L), "patients prepared", toNurse.getOrDefault(id, 0L), "to prepare");
                    case PHARMACIST -> new StaffRow(u.getPublicId(), u.getFullName(), role.name(),
                            submitted.get("PHARMACY").getOrDefault(id, 0L), "reviews submitted",
                            waiting.get("PHARMACY").getOrDefault(id, 0L), "waiting");
                    case LABORATORY -> new StaffRow(u.getPublicId(), u.getFullName(), role.name(),
                            submitted.get("LABORATORY").getOrDefault(id, 0L), "reviews submitted",
                            waiting.get("LABORATORY").getOrDefault(id, 0L), "waiting");
                    case HIM -> new StaffRow(u.getPublicId(), u.getFullName(), role.name(),
                            prepared.getOrDefault(id, 0L), "records prepared", toPrepare.getOrDefault(id, 0L), "to prepare");
                });
            }
        }
        return rows;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private record Window(LocalDate from, LocalDate to, LocalDateTime fromUtc, LocalDateTime toUtc) {
        Window(LocalDate from, LocalDate to) {
            this(from, to, HospitalClock.toUtc(from, LocalTime.MIDNIGHT),
                    HospitalClock.toUtc(to.plusDays(1), LocalTime.MIDNIGHT));
        }

        Map<String, Object> params(Map<String, Object> base) {
            return with(base, "f", fromUtc, "t", toUtc);
        }
    }

    private static void checkWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Give a start date on or before the end date.");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("Choose a window of " + MAX_WINDOW_DAYS + " days or less.");
        }
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        Map<String, Object> out = new HashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> paged(Map<String, Object> base, int page, int size) {
        return with(base, "lim", size, "off", page * size);
    }

    private Query bind(String sql, Map<String, Object> params) {
        Query query = em.createNativeQuery(sql);
        // Only the parameters this statement names: :t must not match :type.
        params.forEach((k, v) -> {
            if (v != null && java.util.regex.Pattern.compile(":" + k + "\\b").matcher(sql).find()) {
                query.setParameter(k, v);
            }
        });
        return query;
    }

    private Object single(String sql, Map<String, Object> params) {
        return bind(sql, params).getSingleResult();
    }

    private long count(String sql, Map<String, Object> params) {
        return toLong(single(sql, params));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Map<String, Object> params) {
        return bind(sql, params).getResultList();
    }

    private Map<Long, Long> grouped(String sql, Map<String, Object> params) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : rows(sql, params)) {
            if (r[0] != null) {
                out.put(toLong(r[0]), toLong(r[1]));
            }
        }
        return out;
    }

    /** Counts per WAT day of {@code column} for rows matching {@code fromWhere}, inside the window. */
    private Map<LocalDate, Long> daily(String column, String fromWhere, Map<String, Object> params) {
        Map<LocalDate, Long> out = new HashMap<>();
        for (Object[] r : rows("SELECT DATE(" + column + " + INTERVAL 1 HOUR) AS d, COUNT(*) FROM " + fromWhere
                + " AND " + column + " >= :f AND " + column + " < :t GROUP BY d", params)) {
            LocalDate day = date(r[0]);
            if (day != null) {
                out.put(day, toLong(r[1]));
            }
        }
        return out;
    }

    /** Every day in the window, zero-filled, with one value per series. */
    private static List<Day> days(Window w, Map<String, Map<LocalDate, Long>> series) {
        List<Day> out = new ArrayList<>();
        for (LocalDate d = w.from; !d.isAfter(w.to); d = d.plusDays(1)) {
            Map<String, Long> values = new TreeMap<>();
            for (Map.Entry<String, Map<LocalDate, Long>> s : series.entrySet()) {
                values.put(s.getKey(), s.getValue().getOrDefault(d, 0L));
            }
            out.add(new Day(d, values));
        }
        return out;
    }

    private Metric avg(String key, String label, String format, String sql, Map<String, Object> params) {
        Object value = single(sql, params);
        Double rounded = value == null ? null
                : new BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new Metric(key, label, rounded, format, null, null, null);
    }

    private static Metric percent(String key, String label, long part, long whole) {
        Double value = whole == 0 ? null
                : BigDecimal.valueOf(part * 100.0 / whole).setScale(0, RoundingMode.HALF_UP).doubleValue();
        return new Metric(key, label, value, "percent", null, null, null);
    }

    private static Double hoursBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return BigDecimal.valueOf(java.time.Duration.between(start, end).toMinutes() / 60.0)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        return false;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime dateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
