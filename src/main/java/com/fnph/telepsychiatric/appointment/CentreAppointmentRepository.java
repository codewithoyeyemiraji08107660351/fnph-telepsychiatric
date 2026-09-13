package com.fnph.telepsychiatric.appointment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped automatically by the repository base class. */
public interface CentreAppointmentRepository extends JpaRepository<CentreAppointment, Long> {

    Optional<CentreAppointment> findByPublicId(String publicId);
    Optional<CentreAppointment> findByReference(String reference);
    Optional<CentreAppointment> findBySlotId(Long slotId);

    /**
     * The Hub Coordinator's centre queue.
     *
     * Deliberately unfiltered by centre: FNPH staff work across every centre,
     * and the tenant filter is not applied to them.
     */

    Page<CentreAppointment> findAllByStatusOrderByAppointmentDateTimeAsc(Status status,
                                                                         Pageable pageable);

    List<CentreAppointment> findAllByCentrePatientIdOrderByAppointmentDateTimeDesc(Long centrePatientId);


    long countByStatus(Status status);
}
