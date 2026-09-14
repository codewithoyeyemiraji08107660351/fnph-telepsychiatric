package com.fnph.telepsychiatric.appointment;

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
 * <h2>Tenant scoping is not uniform across these methods</h2>
 *
 * {@code findById}, {@code existsById} and {@code getReferenceById} inherit an
 * explicit ownership check from {@link
 * com.fnph.telepsychiatric.tenancy.TenantAwareRepository}. The derived methods
 * below do not pass through it, and the Hibernate filter is only enabled inside
 * those inherited methods. Until that is fixed, validate the centre in the
 * service layer before returning anything from a derived lookup to a centre
 * principal.
 */
public interface CentreAppointmentRepository extends JpaRepository<CentreAppointment, Long> {

    Optional<CentreAppointment> findByPublicId(String publicId);

    Optional<CentreAppointment> findByCentreIdAndPublicId(Long centreId, String publicId);

    Optional<CentreAppointment> findByReference(String reference);

    Optional<CentreAppointment> findBySlotId(Long slotId);

    /**
     * The Hub Coordinator's centre queue.
     *
     * Deliberately unfiltered by centre: FNPH staff work across every centre,
     * and the tenant filter is not enabled for a hospital-scoped principal.
     */
    Page<CentreAppointment> findAllByStatusOrderByAppointmentDateAsc(Status status,
                                                                     Pageable pageable);

    List<CentreAppointment> findAllByCentrePatientIdOrderByAppointmentDateDesc(Long centrePatientId);

    long countByStatus(Status status);


}