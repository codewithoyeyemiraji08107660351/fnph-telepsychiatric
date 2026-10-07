package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.common.HospitalClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Numbers for the Hub Coordinator dashboard. FNPH pathway only.
 *
 * Aggregates run in the database so the dashboard costs a fixed number of
 * queries however many consultations there are. Native SQL because several of
 * these join across entities with no JPA relationship between them.
 *
 * <h2>Two kinds of number</h2>
 * {@code now}: the state of the queues at this moment, ignoring the window.
 * {@code period}: what happened inside the chosen window of hospital days.
 *
 * <h2>Counted when it happened</h2>
 * Approvals, rejections, cancellations, reschedules, no-shows and new requests
 * are counted from appointment_status_history at the moment of the decision,
 * not by the appointment's date. "Approved this week" means approved this week,
 * whenever the consultation itself is booked for.
 *
 * <h2>Day boundaries</h2>
 * Stored times are UTC. Hospital days are West Africa Time, UTC+1 all year
 * (no daylight saving), so daily grouping adds one hour before taking the date.
 */
@Service
@Transactional(readOnly = true)
public class HubStatsService {

    public static final int MAX_WINDOW_DAYS = 366;

    /** Reviews whose document sits in an FNPH bundle (centre bundles have no appointment). */
    private static final String FNPH_REVIEWS = """
            FROM professional_reviews r
            LEFT JOIN prescriptions p ON p.id = r.prescription_id
            LEFT JOIN investigations i ON i.id = r.investigation_id
            JOIN release_bundles rb ON rb.id = COALESCE(p.bundle_id, i.bundle_id)
            WHERE rb.appointment_id IS NOT NULL
            """;

    /** Statuses whose team was actually committed to the slot. */
    private static final String STAFFED = "('APPROVED','IN_PROGRESS','COMPLETED','NO_SHOW')";

    /**
     * One status-history row classified as a hub event, or NULL when it is not
     * one the dashboard counts (team and room notes keep the same status).
     *
     * Lifecycle transitions (cancel, reschedule, no-show) write no from_status,
     * so the reschedule is recognised by the reason its writer always prefixes.
     */
    private static final String EVENT_KIND = """
            CASE
                WHEN h.to_status = 'AWAITING_APPROVAL' AND h.from_status = 'SLOT_HELD' THEN 'REQUESTED'
                WHEN h.to_status = 'AWAITING_APPROVAL' AND h.reason LIKE 'Rescheduled:%' THEN 'RESCHEDULED'
                WHEN h.to_status = 'APPROVED' AND h.from_status = 'AWAITING_APPROVAL' THEN 'APPROVED'
                WHEN h.to_status = 'REJECTED' THEN 'REJECTED'
                WHEN h.to_status = 'CANCELLED' THEN 'CANCELLED'
                WHEN h.to_status = 'NO_SHOW' THEN 'NO_SHOW'
            END
            """;

    @PersistenceContext
    private EntityManager em;

    public record Now(
            long awaitingApproval,
            /** When the longest-waiting request reached the desk. Null when the desk is empty. */
            LocalDateTime oldestAwaitingSince,
            long upcoming,
            long upcomingNext7Days,
            long sessionsToday,
            long inSession,
            long reviewsUnassigned,
            long reviewsPending,
            long queriesOpen,
            long bundlesIncomplete,
            long bundlesReady,
            long bundlesHeld,
            long followUpsToSchedule,
            long followUpsOverdue) {
    }

    public record Period(
            /** Paid booking requests that reached the desk. */
            long requests,
            long approved,
            long rejected,
            /** approved / (approved + rejected), as a percentage. Null when no decisions. */
            Double approvalRate,
            long rescheduled,
            long cancelled,
            long sessionsHeld,
            long completed,
            long noShows,
            long released,
            long queriesRaised,
            long hubEdits,
            /** Consultations dated inside the window, by their current status. */
            Map<String, Long> scheduledByStatus,
            Double avgHoursRequestToDecision,
            Double avgHoursReviewTurnaround,
            Double avgHoursSessionToRelease) {
    }

    public record Day(LocalDate date, long requests, long approved, long rejected,
                      long completed, long released) {
    }

    public record Workload(String role, String name, long consultations, long reviewsPending) {
    }

    public record Stats(LocalDate from, LocalDate to, Now now, Period period,
                        List<Day> daily, List<Workload> workload) {
    }

    public Stats stats(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Give a start date on or before the end date.");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("Choose a window of " + MAX_WINDOW_DAYS + " days or less.");
        }

        LocalDateTime start = HospitalClock.toUtc(from, LocalTime.MIDNIGHT);
        LocalDateTime end = HospitalClock.toUtc(to.plusDays(1), LocalTime.MIDNIGHT);

        return new Stats(from, to, now(), period(start, end), daily(from, to, start, end), workload(start, end));
    }

    // -----------------------------------------------------------------

    private Now now() {
        LocalDate today = HospitalClock.today();
        LocalDateTime instant = LocalDateTime.now();
        LocalDateTime todayStart = HospitalClock.toUtc(today, LocalTime.MIDNIGHT);
        LocalDateTime todayEnd = HospitalClock.toUtc(today.plusDays(1), LocalTime.MIDNIGHT);

        Map<String, Long> bundles = grouped(
                "SELECT status, COUNT(*) FROM release_bundles "
                        + "WHERE appointment_id IS NOT NULL AND status <> 'RELEASED' GROUP BY status",
                Map.of());

        LocalDateTime oldest = dateTime(query("""
                SELECT MIN(w.since) FROM (
                    SELECT MAX(h.changed_at) AS since
                    FROM appointments a
                    JOIN appointment_status_history h
                      ON h.appointment_id = a.id AND h.to_status = 'AWAITING_APPROVAL'
                    WHERE a.status = 'AWAITING_APPROVAL'
                    GROUP BY a.id
                ) w
                """, Map.of()).getSingleResult());

        return new Now(
                count("SELECT COUNT(*) FROM appointments WHERE status = 'AWAITING_APPROVAL'", Map.of()),
                oldest,
                count("SELECT COUNT(*) FROM appointments WHERE status = 'APPROVED' AND appointment_date >= :now",
                        Map.of("now", instant)),
                count("SELECT COUNT(*) FROM appointments WHERE status = 'APPROVED' "
                                + "AND appointment_date >= :now AND appointment_date < :week",
                        Map.of("now", instant, "week", instant.plusDays(7))),
                count("SELECT COUNT(*) FROM appointments WHERE status IN ('APPROVED','IN_PROGRESS','COMPLETED') "
                                + "AND appointment_date >= :s AND appointment_date < :e",
                        Map.of("s", todayStart, "e", todayEnd)),
                count("SELECT COUNT(*) FROM appointments WHERE status = 'IN_PROGRESS'", Map.of()),
                count("SELECT COUNT(*) " + FNPH_REVIEWS
                        + " AND r.submitted_at IS NULL AND r.reviewer_id IS NULL", Map.of()),
                count("SELECT COUNT(*) " + FNPH_REVIEWS
                        + " AND r.submitted_at IS NULL AND r.reviewer_id IS NOT NULL", Map.of()),
                count("SELECT COUNT(*) " + FNPH_REVIEWS
                        + " AND r.query_raised = b'1' AND rb.status <> 'RELEASED'", Map.of()),
                bundles.getOrDefault("INCOMPLETE", 0L),
                bundles.getOrDefault("READY", 0L),
                bundles.getOrDefault("BLOCKED", 0L),
                count("SELECT COUNT(*) FROM follow_ups WHERE consultation_id IS NOT NULL "
                        + "AND status = 'RECOMMENDED'", Map.of()),
                count("SELECT COUNT(*) FROM follow_ups WHERE consultation_id IS NOT NULL "
                                + "AND status = 'SCHEDULED' AND scheduled_date < :today",
                        Map.of("today", today)));
    }

    private Period period(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> window = Map.of("s", start, "e", end);

        Map<String, Long> events = grouped(
                "SELECT k.kind, COUNT(*) FROM (SELECT " + EVENT_KIND + " AS kind "
                        + "FROM appointment_status_history h WHERE h.appointment_id IS NOT NULL "
                        + "AND h.changed_at >= :s AND h.changed_at < :e) k "
                        + "WHERE k.kind IS NOT NULL GROUP BY k.kind",
                window);

        long approved = events.getOrDefault("APPROVED", 0L);
        long rejected = events.getOrDefault("REJECTED", 0L);
        Double approvalRate = approved + rejected == 0 ? null
                : BigDecimal.valueOf(approved * 100.0 / (approved + rejected))
                .setScale(1, RoundingMode.HALF_UP).doubleValue();

        Map<String, Long> scheduled = grouped(
                "SELECT status, COUNT(*) FROM appointments "
                        + "WHERE appointment_date >= :s AND appointment_date < :e "
                        + "AND status NOT IN ('SLOT_HELD','EXPIRED') GROUP BY status",
                window);

        return new Period(
                events.getOrDefault("REQUESTED", 0L),
                approved,
                rejected,
                approvalRate,
                events.getOrDefault("RESCHEDULED", 0L),
                events.getOrDefault("CANCELLED", 0L),
                count("SELECT COUNT(*) FROM consultations c WHERE c.appointment_id IS NOT NULL "
                        + "AND c.started_at >= :s AND c.started_at < :e", window),
                count("SELECT COUNT(*) FROM consultations c JOIN appointments a ON a.id = c.appointment_id "
                        + "WHERE a.status = 'COMPLETED' AND c.ended_at >= :s AND c.ended_at < :e", window),
                events.getOrDefault("NO_SHOW", 0L),
                count("SELECT COUNT(*) FROM release_bundles WHERE appointment_id IS NOT NULL "
                        + "AND released_at >= :s AND released_at < :e", window),
                count("SELECT COUNT(*) " + FNPH_REVIEWS
                        + " AND r.query_raised = b'1' AND r.submitted_at >= :s AND r.submitted_at < :e", window),
                count("SELECT COUNT(DISTINCT edit_group) FROM clinical_edit_revisions "
                        + "WHERE created_at >= :s AND created_at < :e", window),
                scheduled,
                // Request (payment confirmed) to the coordinator's decision, either way.
                hours("""
                        SELECT AVG(TIMESTAMPDIFF(MINUTE, rq.changed_at, dn.changed_at))
                        FROM appointment_status_history dn
                        JOIN appointment_status_history rq
                          ON rq.appointment_id = dn.appointment_id
                         AND rq.to_status = 'AWAITING_APPROVAL' AND rq.from_status = 'SLOT_HELD'
                        WHERE dn.appointment_id IS NOT NULL
                          AND dn.from_status = 'AWAITING_APPROVAL'
                          AND dn.to_status IN ('APPROVED','REJECTED')
                          AND dn.changed_at >= :s AND dn.changed_at < :e
                        """, window),
                hours("SELECT AVG(TIMESTAMPDIFF(MINUTE, r.assigned_at, r.submitted_at)) " + FNPH_REVIEWS
                        + " AND r.assigned_at IS NOT NULL AND r.submitted_at >= :s AND r.submitted_at < :e", window),
                hours("SELECT AVG(TIMESTAMPDIFF(MINUTE, c.ended_at, rb.released_at)) FROM release_bundles rb "
                        + "JOIN consultations c ON c.appointment_id = rb.appointment_id "
                        + "WHERE rb.released_at >= :s AND rb.released_at < :e AND c.ended_at IS NOT NULL", window));
    }

    private List<Day> daily(LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> window = Map.of("s", start, "e", end);

        @SuppressWarnings("unchecked")
        List<Object[]> eventRows = query(
                "SELECT k.d, k.kind, COUNT(*) FROM (SELECT DATE(h.changed_at + INTERVAL 1 HOUR) AS d, "
                        + EVENT_KIND + " AS kind FROM appointment_status_history h "
                        + "WHERE h.appointment_id IS NOT NULL AND h.changed_at >= :s AND h.changed_at < :e) k "
                        + "WHERE k.kind IN ('REQUESTED','APPROVED','REJECTED') GROUP BY k.d, k.kind",
                window).getResultList();

        Map<String, Map<LocalDate, Long>> events = new HashMap<>();
        for (Object[] row : eventRows) {
            if (row[0] != null) {
                events.computeIfAbsent(String.valueOf(row[1]), k -> new HashMap<>())
                        .put(LocalDate.parse(row[0].toString()), toLong(row[2]));
            }
        }

        Map<LocalDate, Long> completed = byDay(
                "SELECT DATE(c.ended_at + INTERVAL 1 HOUR) d, COUNT(*) FROM consultations c "
                        + "JOIN appointments a ON a.id = c.appointment_id "
                        + "WHERE a.status = 'COMPLETED' AND c.ended_at >= :s AND c.ended_at < :e GROUP BY d",
                window);
        Map<LocalDate, Long> released = byDay(
                "SELECT DATE(released_at + INTERVAL 1 HOUR) d, COUNT(*) FROM release_bundles "
                        + "WHERE appointment_id IS NOT NULL AND released_at >= :s AND released_at < :e "
                        + "GROUP BY d", window);

        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(new Day(d,
                    events.getOrDefault("REQUESTED", Map.of()).getOrDefault(d, 0L),
                    events.getOrDefault("APPROVED", Map.of()).getOrDefault(d, 0L),
                    events.getOrDefault("REJECTED", Map.of()).getOrDefault(d, 0L),
                    completed.getOrDefault(d, 0L),
                    released.getOrDefault(d, 0L)));
        }
        return days;
    }

    private List<Workload> workload(LocalDateTime start, LocalDateTime end) {
        String window = " WHERE appointment_date >= :s AND appointment_date < :e AND status IN " + STAFFED;

        @SuppressWarnings("unchecked")
        List<Object[]> assigned = query("""
                SELECT t.role, u.id, CONCAT(u.first_name, ' ', u.last_name), COUNT(*)
                FROM (
                    SELECT 'DOCTOR' AS role, doctor_id AS user_id FROM appointments %1$s
                    UNION ALL SELECT 'NURSE', nurse_id FROM appointments %1$s
                    UNION ALL SELECT 'PHARMACIST', pharmacist_id FROM appointments %1$s
                    UNION ALL SELECT 'LABORATORY', laboratory_technician_id FROM appointments %1$s
                    UNION ALL SELECT 'HIM', him_officer_id FROM appointments %1$s
                ) t
                JOIN users u ON u.id = t.user_id
                GROUP BY t.role, u.id, u.first_name, u.last_name
                """.formatted(window), Map.of("s", start, "e", end)).getResultList();

        @SuppressWarnings("unchecked")
        List<Object[]> pending = query("""
                SELECT CASE r.review_type WHEN 'PHARMACY' THEN 'PHARMACIST' ELSE 'LABORATORY' END,
                       r.reviewer_id, COUNT(*)
                """ + FNPH_REVIEWS + """
                 AND r.submitted_at IS NULL AND r.reviewer_id IS NOT NULL
                GROUP BY r.review_type, r.reviewer_id
                """, Map.of()).getResultList();

        Map<String, Long> pendingByKey = new HashMap<>();
        for (Object[] row : pending) {
            pendingByKey.put(row[0] + ":" + toLong(row[1]), toLong(row[2]));
        }

        List<String> order = List.of("DOCTOR", "NURSE", "PHARMACIST", "LABORATORY", "HIM");
        List<Workload> rows = new ArrayList<>();
        for (Object[] row : assigned) {
            String role = String.valueOf(row[0]);
            rows.add(new Workload(role, String.valueOf(row[2]), toLong(row[3]),
                    pendingByKey.getOrDefault(role + ":" + toLong(row[1]), 0L)));
        }
        rows.sort(Comparator.comparingInt((Workload w) -> order.indexOf(w.role()))
                .thenComparing(Comparator.comparingLong(Workload::consultations).reversed())
                .thenComparing(Workload::name));
        return rows;
    }

    // -----------------------------------------------------------------

    private Query query(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q;
    }

    private long count(String sql, Map<String, Object> params) {
        return toLong(query(sql, params).getSingleResult());
    }

    private Double hours(String sql, Map<String, Object> params) {
        Object value = query(sql, params).getSingleResult();
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.toString())
                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> grouped(String sql, Map<String, Object> params) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : (List<Object[]>) query(sql, params).getResultList()) {
            out.put(String.valueOf(row[0]), toLong(row[1]));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<LocalDate, Long> byDay(String sql, Map<String, Object> params) {
        Map<LocalDate, Long> out = new HashMap<>();
        for (Object[] row : (List<Object[]>) query(sql, params).getResultList()) {
            if (row[0] != null) {
                // java.sql.Date and LocalDate both print as yyyy-MM-dd.
                out.put(LocalDate.parse(row[0].toString()), toLong(row[1]));
            }
        }
        return out;
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** Native DATETIME comes back as Timestamp or LocalDateTime depending on the driver. */
    static LocalDateTime dateTime(Object value) {
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
}