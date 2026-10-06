package com.fnph.telepsychiatric.hub;

import com.fnph.telepsychiatric.common.HospitalClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @PersistenceContext
    private EntityManager em;

    public record Now(
            long awaitingApproval,
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
            long consultations,
            long completed,
            long noShows,
            long cancelled,
            long rejected,
            long released,
            long queriesRaised,
            long hubEdits,
            Double avgHoursBookedToApproved,
            Double avgHoursReviewTurnaround,
            Double avgHoursSessionToRelease) {
    }

    public record Day(LocalDate date, long approved, long completed, long released) {
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
        LocalDateTime todayStart = HospitalClock.toUtc(today, LocalTime.MIDNIGHT);
        LocalDateTime todayEnd = HospitalClock.toUtc(today.plusDays(1), LocalTime.MIDNIGHT);

        Map<String, Long> bundles = grouped(
                "SELECT status, COUNT(*) FROM release_bundles "
                        + "WHERE appointment_id IS NOT NULL AND status <> 'RELEASED' GROUP BY status",
                Map.of());

        return new Now(
                count("SELECT COUNT(*) FROM appointments WHERE status = 'AWAITING_APPROVAL'", Map.of()),
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

        Map<String, Long> byStatus = grouped(
                "SELECT status, COUNT(*) FROM appointments "
                        + "WHERE appointment_date >= :s AND appointment_date < :e "
                        + "AND status NOT IN ('SLOT_HELD','EXPIRED') GROUP BY status",
                window);
        long consultations = byStatus.values().stream().mapToLong(Long::longValue).sum();

        return new Period(
                consultations,
                byStatus.getOrDefault("COMPLETED", 0L),
                byStatus.getOrDefault("NO_SHOW", 0L),
                byStatus.getOrDefault("CANCELLED", 0L),
                byStatus.getOrDefault("REJECTED", 0L),
                count("SELECT COUNT(*) FROM release_bundles WHERE appointment_id IS NOT NULL "
                        + "AND released_at >= :s AND released_at < :e", window),
                count("SELECT COUNT(*) " + FNPH_REVIEWS
                        + " AND r.query_raised = b'1' AND r.submitted_at >= :s AND r.submitted_at < :e", window),
                count("SELECT COUNT(DISTINCT edit_group) FROM clinical_edit_revisions "
                        + "WHERE created_at >= :s AND created_at < :e", window),
                hours("SELECT AVG(TIMESTAMPDIFF(MINUTE, created_at, approved_at)) FROM appointments "
                        + "WHERE approved_at >= :s AND approved_at < :e", window),
                hours("SELECT AVG(TIMESTAMPDIFF(MINUTE, r.assigned_at, r.submitted_at)) " + FNPH_REVIEWS
                        + " AND r.assigned_at IS NOT NULL AND r.submitted_at >= :s AND r.submitted_at < :e", window),
                hours("SELECT AVG(TIMESTAMPDIFF(MINUTE, c.ended_at, rb.released_at)) FROM release_bundles rb "
                        + "JOIN consultations c ON c.appointment_id = rb.appointment_id "
                        + "WHERE rb.released_at >= :s AND rb.released_at < :e AND c.ended_at IS NOT NULL", window));
    }

    private List<Day> daily(LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> window = Map.of("s", start, "e", end);

        Map<LocalDate, Long> approved = byDay(
                "SELECT DATE(approved_at + INTERVAL 1 HOUR) d, COUNT(*) FROM appointments "
                        + "WHERE approved_at >= :s AND approved_at < :e GROUP BY d", window);
        Map<LocalDate, Long> completed = byDay(
                "SELECT DATE(appointment_date + INTERVAL 1 HOUR) d, COUNT(*) FROM appointments "
                        + "WHERE status = 'COMPLETED' AND appointment_date >= :s AND appointment_date < :e "
                        + "GROUP BY d", window);
        Map<LocalDate, Long> released = byDay(
                "SELECT DATE(released_at + INTERVAL 1 HOUR) d, COUNT(*) FROM release_bundles "
                        + "WHERE appointment_id IS NOT NULL AND released_at >= :s AND released_at < :e "
                        + "GROUP BY d", window);

        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(new Day(d, approved.getOrDefault(d, 0L),
                    completed.getOrDefault(d, 0L), released.getOrDefault(d, 0L)));
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
}
