package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vitals capture.
 *
 * <h2>BMI is derived, never accepted</h2>
 *
 * A client that sends its own BMI can send one that does not match the height
 * and weight beside it, and the clinician reading the screen has no way to tell
 * which is wrong. It is computed here from the two values that were measured.
 *
 * <h2>Out-of-range values are rejected, not flagged</h2>
 *
 * A systolic pressure of 1200 is a typo, and a clinician who sees it on a
 * consultation screen has to stop and ask rather than assess. Catching it at
 * entry costs one method; catching it mid-consultation costs the session.
 *
 * The bounds are deliberately wide. They catch a slipped decimal point, not
 * an unusual patient.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VitalsService {

    private final VitalsRepository vitalsRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuditService auditService;

    @Transactional
    public Vitals record(String appointmentPublicId, VitalsEntry entry) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new VitalsException("No such appointment"));

        validate(appointment, entry);

        Vitals vitals = new Vitals();
        vitals.setAppointment(appointment);
        vitals.setPatient(appointment.getPatient());
        vitals.setBloodPressureSystolic(entry.systolic());
        vitals.setBloodPressureDiastolic(entry.diastolic());
        vitals.setHeartRate(entry.heartRate());
        vitals.setRespiratoryRate(entry.respiratoryRate());
        vitals.setTemperature(entry.temperature());
        vitals.setWeightKg(entry.weightKg());
        vitals.setHeightCm(entry.heightCm());
        vitals.setBloodOxygen(entry.bloodOxygen());
        vitals.setBloodGlucose(entry.bloodGlucose());
        vitals.setNotes(entry.notes());
        vitals.setMeasuredAt(entry.measuredAt() == null ? LocalDateTime.now() : entry.measuredAt());
        vitals.setMeasurementSource(entry.measurementSource());
        vitals.setBmi(deriveBmi(entry.weightKg(), entry.heightCm()));

        Vitals saved = vitalsRepository.save(vitals);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("Vitals")
                .entityId(saved.getId())
                .details("Vitals recorded for appointment " + appointment.getReference())
                .build());

        return saved;
    }

    @Transactional(readOnly = true)
    public Set<Long> appointmentsWithVitals(Collection<Long> appointmentIds) {

        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return Set.of();
        }

        return vitalsRepository
                .findAppointmentIdsWithVitals(appointmentIds)
                .stream()
                .collect(Collectors.toSet());
    }

    /**
     * Nurse verification.
     *
     * A patient-reported reading and a nurse-measured one are different things,
     * and a clinician deciding on medication needs to know which they are
     * looking at.
     */
    @Transactional
    public Vitals verify(String vitalsPublicId) {
        Vitals vitals = vitalsRepository.findByPublicId(vitalsPublicId)
                .orElseThrow(() -> new VitalsException("No such vitals record"));

        vitals.setVerifiedAt(LocalDateTime.now());
        vitals.setVerifiedBy(CurrentUser.usernameOrSystem());
        return vitalsRepository.save(vitals);
    }

    @Transactional(readOnly = true)
    public List<Vitals> forAppointment(Long appointmentId) {
        return vitalsRepository.findAllByAppointmentIdOrderByMeasuredAtDesc(appointmentId);
    }

    @Transactional(readOnly = true)
    public List<Vitals> forPatient(Long patientId) {
        return vitalsRepository.findAllByPatientIdOrderByMeasuredAtDesc(patientId);
    }

    /**
     * Computed from the measured height and weight. Never taken from the client.
     */
    private Double deriveBmi(Double weightKg, Double heightCm) {
        if (weightKg == null || heightCm == null || heightCm <= 0) {
            return null;
        }
        double metres = heightCm / 100.0;
        return Math.round((weightKg / (metres * metres)) * 10.0) / 10.0;
    }

    /**
     * Range checks, plus the ownership check.
     *
     * The appointment is passed in rather than read from a field. It used to be
     * an injected field, which is what stopped the application starting:
     * Appointment is a JPA entity, so Spring has no bean to give, and Lombok's
     * @RequiredArgsConstructor turned the field into a constructor parameter.
     *
     * Worth noting that if it had somehow started, the check would have been
     * reading one shared instance for the life of the application rather than
     * the appointment being recorded, so a patient could have written vitals
     * against anyone's appointment.
     */
    private void validate(Appointment appointment, VitalsEntry e) {
        // Ownership first. A patient may only record against their own
        // appointment, and there is no reason to range-check a reading that is
        // about to be refused.
        //
        // The message is deliberately the same as a missing appointment. Saying
        // "that is not yours" confirms the appointment exists, which lets
        // someone walk the identifiers to discover who has appointments at a
        // neuropsychiatric hospital.
        Long callerPatientId = CurrentUser.patientId().orElse(null);
        if (callerPatientId != null
                && !callerPatientId.equals(appointment.getPatient().getId())) {
            throw new VitalsException("No such appointment");
        }

        // Wide on purpose. These catch a slipped decimal point, not an unusual
        // patient, and a rule that rejects a real reading is worse than none.
        range("Systolic blood pressure", e.systolic(), 50, 300);
        range("Diastolic blood pressure", e.diastolic(), 20, 200);
        range("Heart rate", e.heartRate(), 20, 250);
        range("Respiratory rate", e.respiratoryRate(), 4, 80);
        range("Blood oxygen", e.bloodOxygen(), 50, 100);
        range("Temperature", e.temperature(), 30.0, 45.0);
        range("Weight", e.weightKg(), 1.0, 400.0);
        range("Height", e.heightCm(), 30.0, 250.0);
        range("Blood glucose", e.bloodGlucose(), 1.0, 50.0);

        if (e.systolic() != null && e.diastolic() != null && e.diastolic() >= e.systolic()) {
            throw new VitalsException(
                    "Diastolic pressure cannot be at or above systolic. Check the two values "
                            + "have not been swapped.");
        }
        if (e.measuredAt() != null && e.measuredAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new VitalsException("The measurement time is in the future");
        }
    }

    private void range(String label, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new VitalsException(
                    "%s of %d is outside the plausible range %d to %d. Check for a typo."
                            .formatted(label, value, min, max));
        }
    }

    private void range(String label, Double value, double min, double max) {
        if (value != null && (value < min || value > max)) {
            throw new VitalsException(
                    "%s of %s is outside the plausible range %s to %s. Check for a typo."
                            .formatted(label, value, min, max));
        }
    }

    public record VitalsEntry(Integer systolic, Integer diastolic, Integer heartRate,
                              Integer respiratoryRate, Double temperature, Double weightKg,
                              Double heightCm, Integer bloodOxygen, Double bloodGlucose,
                              LocalDateTime measuredAt, String measurementSource, String notes) {
    }

    public static class VitalsException extends RuntimeException {
        public VitalsException(String message) {
            super(message);
        }
    }
}
