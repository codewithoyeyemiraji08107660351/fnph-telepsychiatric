package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Whether a doctor already has a consultation at a given time, on either
 * pathway.
 *
 * FNPH and centre consultations live in separate tables. Each approval
 * checked only its own (FNPH approval checked neither), so the same doctor
 * could be approved for an FNPH patient and a centre patient at the same
 * time, and one of them would sit in an empty room.
 */
@Component
@RequiredArgsConstructor
public class DoctorCalendar {

    /** Never a real id: excludes nothing. */
    private static final long NONE = -1L;

    private final AppointmentRepository appointmentRepository;
    private final CentreAppointmentRepository centreAppointmentRepository;

    /**
     * @param exceptFnphAppointmentId   the FNPH appointment being decided, so it
     *                                  does not clash with itself; null if none
     * @param exceptCentreAppointmentId the centre appointment being decided; null if none
     */
    @Transactional(readOnly = true)
    public boolean busy(Long doctorId, LocalDateTime start, LocalDateTime end,
                        Long exceptFnphAppointmentId, Long exceptCentreAppointmentId) {
        if (doctorId == null || start == null || end == null) {
            return false;
        }
        return appointmentRepository.doctorBusy(doctorId,
                        exceptFnphAppointmentId == null ? NONE : exceptFnphAppointmentId, start, end)
                || centreAppointmentRepository.doctorBusy(doctorId,
                        exceptCentreAppointmentId == null ? NONE : exceptCentreAppointmentId, start, end);
    }
}
