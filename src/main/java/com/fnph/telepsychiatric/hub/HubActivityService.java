package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.common.HospitalClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hospital-wide activity feed for the Hub Coordinator: every decision, session,
 * team change, review, release and hub edit across FNPH consultations, newest
 * first.
 *
 * Built from records that already exist. Nothing new is stored. One UNION query
 * per page, assembled from only the groups asked for, then one query to name the
 * consultations on the page.
 *
 * Paged by time ({@code before}), not by offset, so a page never shifts when new
 * activity arrives while the coordinator is reading.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubActivityService {

    public static final int MAX_WINDOW_DAYS = 366;
    public static final int MAX_LIMIT = 100;

    /** What the coordinator can filter by. */
    public enum Group { REQUESTED, APPROVED, REJECTED, CHANGED, SESSION, TEAM, REVIEW, RELEASE, EDIT }

    public enum Type {
        BOOKING_REQUESTED, RESCHEDULED, APPROVED, REJECTED, CANCELLED, NO_SHOW,
        SESSION_STARTED, SESSION_ENDED, TEAM_CHANGED,
        REVIEW_SUBMITTED, QUERY_RAISED, RELEASED, RELEASE_HELD, HUB_EDIT;

        Group group() {
            return switch (this) {
                case BOOKING_REQUESTED -> Group.REQUESTED;
                case APPROVED -> Group.APPROVED;
                case REJECTED -> Group.REJECTED;
                case RESCHEDULED, CANCELLED -> Group.CHANGED;
                case NO_SHOW, SESSION_STARTED, SESSION_ENDED -> Group.SESSION;
                case TEAM_CHANGED -> Group.TEAM;
                case REVIEW_SUBMITTED, QUERY_RAISED -> Group.REVIEW;
                case RELEASED, RELEASE_HELD -> Group.RELEASE;
                case HUB_EDIT -> Group.EDIT;
            };
        }
    }

    public record Item(
            LocalDateTime at,
            Type type,
            Group group,
            String appointmentPublicId,
            String reference,
            String patientName,
            String title,
            String detail,
            String actor) {
    }

    /** {@code nextBefore} is null on the last page. */
    public record Page(LocalDate from, LocalDate to, List<Item> items, LocalDateTime nextBefore) {
    }

    // Every branch returns the same eight aliased columns:
    //   at, type, appt, a, b, c, d, actor
    // Aliased in every branch, not only the first: the first branch names the
    // UNION's columns, and which branch comes first depends on the filter.
    // The meaning of a..d depends on the type and is decoded in describe().

    private static final String STATUS_ROWS = """
            SELECT h.changed_at AS at, '%s' AS type, h.appointment_id AS appt,
                   h.from_status AS a, NULL AS b, NULL AS c, h.reason AS d, h.changed_by AS actor
            FROM appointment_status_history h
            WHERE h.appointment_id IS NOT NULL AND %s
              AND h.changed_at >= :s AND h.changed_at < :e AND h.changed_at < :before
            """;

    private static final Map<Group, List<String>> BRANCHES = Map.of(
            Group.REQUESTED, List.of(
                    STATUS_ROWS.formatted("BOOKING_REQUESTED",
                            "h.to_status = 'AWAITING_APPROVAL' AND h.from_status = 'SLOT_HELD'")),
            Group.APPROVED, List.of(
                    STATUS_ROWS.formatted("APPROVED",
                            "h.to_status = 'APPROVED' AND h.from_status = 'AWAITING_APPROVAL'")),
            Group.REJECTED, List.of(
                    STATUS_ROWS.formatted("REJECTED", "h.to_status = 'REJECTED'")),
            Group.CHANGED, List.of(
                    STATUS_ROWS.formatted("CANCELLED", "h.to_status = 'CANCELLED'"),
                    STATUS_ROWS.formatted("RESCHEDULED",
                            "h.to_status = 'AWAITING_APPROVAL' AND h.reason LIKE 'Rescheduled:%'")),
            Group.SESSION, List.of(
                    STATUS_ROWS.formatted("NO_SHOW", "h.to_status = 'NO_SHOW'"),
                    """
                    SELECT c.started_at AS at, 'SESSION_STARTED' AS type, c.appointment_id AS appt,
                           c.modality AS a, NULL AS b, NULL AS c, NULL AS d, NULL AS actor
                    FROM consultations c
                    WHERE c.appointment_id IS NOT NULL
                      AND c.started_at >= :s AND c.started_at < :e AND c.started_at < :before
                    """,
                    """
                    SELECT c.ended_at AS at, 'SESSION_ENDED' AS type, c.appointment_id AS appt,
                           c.outcome AS a, NULL AS b, NULL AS c, c.termination_note AS d, NULL AS actor
                    FROM consultations c
                    WHERE c.appointment_id IS NOT NULL
                      AND c.ended_at >= :s AND c.ended_at < :e AND c.ended_at < :before
                    """),
            // One row per save: every role changed by one call shares changed_at.
            Group.TEAM, List.of("""
                    SELECT t.changed_at AS at, 'TEAM_CHANGED' AS type, t.appointment_id AS appt,
                           t.change_source AS a,
                           GROUP_CONCAT(CONCAT(t.team_role, '=', COALESCE(t.to_label, ''))
                                        ORDER BY t.id SEPARATOR ';') AS b,
                           NULL AS c, MIN(t.reason) AS d, MIN(t.changed_by) AS actor
                    FROM appointment_team_events t
                    WHERE t.change_source <> 'BACKFILL'
                      AND t.changed_at >= :s AND t.changed_at < :e AND t.changed_at < :before
                    GROUP BY t.appointment_id, t.changed_at, t.change_source
                    """),
            Group.REVIEW, List.of("""
                    SELECT r.submitted_at AS at,
                           CASE WHEN r.query_raised = b'1' THEN 'QUERY_RAISED' ELSE 'REVIEW_SUBMITTED' END AS type,
                           rb.appointment_id AS appt,
                           r.review_type AS a, COALESCE(p.issue_number, i.issue_number) AS b,
                           r.outcome AS c, NULL AS d,
                           CONCAT(u.first_name, ' ', u.last_name) AS actor
                    FROM professional_reviews r
                    LEFT JOIN prescriptions p ON p.id = r.prescription_id
                    LEFT JOIN investigations i ON i.id = r.investigation_id
                    JOIN release_bundles rb ON rb.id = COALESCE(p.bundle_id, i.bundle_id)
                    LEFT JOIN users u ON u.id = r.reviewer_id
                    WHERE rb.appointment_id IS NOT NULL
                      AND r.submitted_at >= :s AND r.submitted_at < :e AND r.submitted_at < :before
                    """),
            Group.RELEASE, List.of(
                    """
                    SELECT rb.released_at AS at, 'RELEASED' AS type, rb.appointment_id AS appt,
                           NULL AS a, NULL AS b, NULL AS c, rb.release_notes AS d, rb.released_by AS actor
                    FROM release_bundles rb
                    WHERE rb.appointment_id IS NOT NULL
                      AND rb.released_at >= :s AND rb.released_at < :e AND rb.released_at < :before
                    """,
                    // A held bundle records no separate "held at"; its last update is the hold.
                    """
                    SELECT rb.updated_at AS at, 'RELEASE_HELD' AS type, rb.appointment_id AS appt,
                           NULL AS a, NULL AS b, NULL AS c, rb.blocked_reason AS d, rb.updated_by AS actor
                    FROM release_bundles rb
                    WHERE rb.appointment_id IS NOT NULL AND rb.status = 'BLOCKED'
                      AND rb.updated_at >= :s AND rb.updated_at < :e AND rb.updated_at < :before
                    """),
            Group.EDIT, List.of("""
                    SELECT MIN(e.created_at) AS at, 'HUB_EDIT' AS type, MIN(e.appointment_id) AS appt,
                           MIN(e.target_type) AS a,
                           GROUP_CONCAT(e.field_name ORDER BY e.id SEPARATOR ', ') AS b,
                           NULL AS c, MIN(e.reason) AS d,
                           MIN(COALESCE(e.edited_by_name, e.edited_by)) AS actor
                    FROM clinical_edit_revisions e
                    WHERE e.created_at >= :s AND e.created_at < :e AND e.created_at < :before
                    GROUP BY e.edit_group
                    """));

    private final AppointmentRepository appointmentRepository;

    @PersistenceContext
    private EntityManager em;

    public Page page(LocalDate from, LocalDate to, Collection<Group> groups,
                     LocalDateTime before, int limit) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Give a start date on or before the end date.");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("Choose a window of " + MAX_WINDOW_DAYS + " days or less.");
        }

        Set<Group> wanted = groups == null || groups.isEmpty()
                ? EnumSet.allOf(Group.class)
                : EnumSet.copyOf(groups);
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));

        // Fixed enum order keeps the generated SQL stable for the plan cache.
        String union = Arrays.stream(Group.values())
                .filter(wanted::contains)
                .flatMap(g -> BRANCHES.get(g).stream())
                .collect(Collectors.joining("\nUNION ALL\n"));

        Query q = em.createNativeQuery(
                "SELECT x.* FROM (\n" + union + "\n) x WHERE x.at IS NOT NULL "
                        + "ORDER BY x.at DESC LIMIT " + size);
        q.setParameter("s", HospitalClock.toUtc(from, LocalTime.MIDNIGHT));
        q.setParameter("e", HospitalClock.toUtc(to.plusDays(1), LocalTime.MIDNIGHT));
        q.setParameter("before", before == null ? LocalDateTime.of(9999, 1, 1, 0, 0) : before);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        Map<Long, Appointment> appointments = named(rows);

        List<Item> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Type type = Type.valueOf(String.valueOf(row[1]));
            Appointment a = row[2] == null ? null : appointments.get(((Number) row[2]).longValue());
            String[] text = describe(type, str(row[3]), str(row[4]), str(row[5]), str(row[6]));
            items.add(new Item(
                    HubStatsService.dateTime(row[0]),
                    type,
                    type.group(),
                    a == null ? null : a.getPublicId(),
                    a == null ? null : a.getReference(),
                    a == null || a.getPatient() == null ? null
                            : (a.getPatient().getFirstName() + " " + a.getPatient().getLastName()).trim(),
                    text[0],
                    text[1],
                    str(row[7])));
        }

        LocalDateTime next = items.size() == size ? items.get(items.size() - 1).at() : null;
        return new Page(from, to, items, next);
    }

    private Map<Long, Appointment> named(List<Object[]> rows) {
        List<Long> ids = rows.stream()
                .map(r -> r[2])
                .filter(v -> v != null)
                .map(v -> ((Number) v).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return appointmentRepository.findWithPatientByIdIn(ids).stream()
                .collect(Collectors.toMap(Appointment::getId, Function.identity()));
    }

    /** Title and detail for one event. Plain sentences; the coordinator reads these. */
    static String[] describe(Type type, String a, String b, String c, String d) {
        return switch (type) {
            case BOOKING_REQUESTED -> new String[]{"Booking request received", d};
            case RESCHEDULED -> new String[]{"Moved to a new time",
                    d == null ? null : d.replaceFirst("^Rescheduled:\\s*", "")};
            case APPROVED -> new String[]{"Approved", d};
            case REJECTED -> new String[]{"Rejected", d};
            case CANCELLED -> new String[]{"Cancelled", d};
            case NO_SHOW -> new String[]{"Recorded as not attended", d};
            case SESSION_STARTED -> new String[]{"Session started", a == null ? null : humanise(a)};
            case SESSION_ENDED -> new String[]{"Session ended",
                    join(a == null ? null : "Outcome: " + humanise(a), d)};
            case TEAM_CHANGED -> new String[]{teamTitle(a), join(team(b), c)};
            case REVIEW_SUBMITTED -> new String[]{reviewKind(a) + " review submitted",
                    join(document(a, b), c == null ? null : humanise(c))};
            case QUERY_RAISED -> new String[]{reviewKind(a) + " query raised", document(a, b)};
            case RELEASED -> new String[]{"Released to the patient", d};
            case RELEASE_HELD -> new String[]{"Release held", d};
            case HUB_EDIT -> new String[]{
                    "Hub edited " + ("REVIEW".equals(a) ? "a review" : "a follow-up")
                            + (b == null ? "" : " (" + b + ")"),
                    d};
        };
    }

    private static String teamTitle(String source) {
        if (source == null) {
            return "Team changed";
        }
        return switch (source) {
            case "APPROVAL" -> "Team set at approval";
            case "ROOM_CHANGE" -> "Room moved";
            case "RESCHEDULE" -> "Team cleared by reschedule";
            case "REASSIGNMENT" -> "Team member reassigned";
            default -> "Team assigned";
        };
    }

    /** "DOCTOR=Ada Obi;ROOM=R2;NURSE=" to "Doctor: Ada Obi, Room: R2, Nurse: removed". */
    private static String team(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        Map<String, String> roles = new HashMap<>(Map.of(
                "DOCTOR", "Doctor", "NURSE", "Nurse", "PHARMACIST", "Pharmacist",
                "LABORATORY", "Laboratory", "HIM", "HIM", "ROOM", "Room"));
        return Arrays.stream(encoded.split(";"))
                .map(part -> {
                    int eq = part.indexOf('=');
                    String role = eq < 0 ? part : part.substring(0, eq);
                    String who = eq < 0 ? "" : part.substring(eq + 1);
                    return roles.getOrDefault(role, humanise(role)) + ": " + (who.isBlank() ? "removed" : who);
                })
                .collect(Collectors.joining(", "));
    }

    private static String reviewKind(String reviewType) {
        return "PHARMACY".equals(reviewType) ? "Pharmacy" : "Laboratory";
    }

    private static String document(String reviewType, String issueNumber) {
        if (issueNumber == null) {
            return null;
        }
        return ("PHARMACY".equals(reviewType) ? "Prescription " : "Investigation ") + issueNumber;
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + ". " + second;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String humanise(String code) {
        return HubWorkflowService.humanise(code);
    }
}