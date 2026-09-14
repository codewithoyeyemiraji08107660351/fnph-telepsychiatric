package com.fnph.telepsychiatric.appointment;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Centre appointments.
 *
 * <h2>Ordering property names track the entity, not the column</h2>
 *
 * These order by {@code appointmentDate}, the property mapped to
 * {@code appointment_date}. Spring Data resolves the name at context startup,
 * so a mismatch compiles cleanly and then refuses to boot.
 *
 * <h2>Tenant scoping</h2>
 *
 * {@code findById}, {@code existsById} and {@code getReferenceById} inherit an
 * ownership check from {@link
 * com.fnph.telepsychiatric.tenancy.TenantAwareRepository}. The derived methods
 * below never pass through it and the Hibernate filter never runs for them.
 *
 * The previous version of this note ended "until that is fixed, validate the
 * centre in the service layer before returning anything from a derived lookup
 * to a centre principal." Nothing validated it. Every method here now either
 * names the centre or says why it does not.
 */
public interface CentreAppointmentRepository extends JpaRepository<CentreAppointment, Long> {

    Optional<CentreAppointment> findByCentreIdAndPublicId(Long centreId, String publicId);

    /**
     * Hub Coordinator and consulting doctor lookup.
     *
     * Reached from {@code /api/v1/hub/centre-approvals/*} and from the
     * consultation room, both FNPH-side. A centre-facing path uses the scoped
     * method above.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "approve, return-to-centre and the consultation room are FNPH staff "
                    + "paths; centre-facing reads use findByCentreIdAndPublicId")
    Optional<CentreAppointment> findByPublicId(String publicId);

    Optional<CentreAppointment> findByCentreIdAndReference(Long centreId, String reference);

    /**
     * One appointment per slot.
     *
     * A slot belongs to FNPH's schedule, not to a centre, so there is no centre
     * to name. Reached only while confirming a slot is still free.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Slot is hospital-owned and has no centre; used to test slot occupancy "
                    + "during booking")
    Optional<CentreAppointment> findBySlotId(Long slotId);

    /**
     * The Hub Coordinator's centre queue.
     *
     * Unfiltered by centre on purpose: FNPH staff work across every centre.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "/api/v1/hub/centre-approvals lists pending appointments from all "
                    + "centres; guarded by appointment.read")
    Page<CentreAppointment> findAllByStatusOrderByAppointmentDateAsc(Status status,
                                                                     Pageable pageable);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centrePatientId resolved via CentrePatientRepository"
                    + ".findByCentreIdAndPublicId at the call site")
    List<CentreAppointment> findAllByCentrePatientIdOrderByAppointmentDateDesc(
            Long centrePatientId);

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "FNPH approval-queue count; centre-facing counts use "
                    + "countByCentreIdAndStatus")
    long countByStatus(Status status);

    long countByCentreIdAndStatus(Long centreId, Status status);
}