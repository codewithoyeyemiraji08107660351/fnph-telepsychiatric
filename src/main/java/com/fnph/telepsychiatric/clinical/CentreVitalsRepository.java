package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CentreVitalsRepository extends JpaRepository<CentreVitals, Long> {

    /** One centre's readings for one of its appointments, newest first. */
    List<CentreVitals> findAllByCentreIdAndCentreAppointmentIdOrderByMeasuredAtDesc(
            Long centreId, Long centreAppointmentId);

    /**
     * Readings for an appointment the consulting doctor has already resolved,
     * newest first.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centreAppointmentId comes from a centre consultation the doctor resolved "
                    + "through their own assignment; the appointment decides the centre")
    List<CentreVitals> findAllByCentreAppointmentIdOrderByMeasuredAtDesc(Long centreAppointmentId);
}
