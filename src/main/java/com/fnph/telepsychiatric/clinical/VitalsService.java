package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.upload.FileCategory;
import com.fnph.telepsychiatric.upload.FileUpload;
import com.fnph.telepsychiatric.upload.FileUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
    private final FileUploadRepository uploadRepository;

    @Transactional
    public Vitals record(String appointmentPublicId, VitalsEntry entry) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new VitalsException("No such appointment"));

        // Patients hold vitals.submit. Without this, one patient could write
        // readings into another patient's record by naming their appointment.
        // Same answer as a missing appointment, so it confirms nothing.
        Long callerPatientId = CurrentUser.patientId().orElse(null);
        if (callerPatientId != null && !callerPatientId.equals(appointment.getPatient().getId())) {
            throw new VitalsException("No such appointment");
        }

        validate(entry);

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

    /**
     * A nurse entering the readings a patient sent as a photo or PDF.
     *
     * The patient took the measurements, so the record stays patient-reported,
     * but the numbers were read off the file by the nurse, who therefore
     * vouches for the transcription: it is saved verified, by them. The file
     * names go into the notes so a clinician can open the original and compare.
     *
     * Every source file must be a vitals or laboratory upload attached to this
     * appointment. A nurse cannot transcribe readings into one patient's record
     * from another patient's file.
     */
    @Transactional
    public Vitals transcribe(String appointmentPublicId, VitalsEntry entry,
                             List<String> sourceUploadIds) {
        Appointment appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new VitalsException("No such appointment"));

        if (sourceUploadIds == null || sourceUploadIds.isEmpty()) {
            throw new VitalsException("Choose the file the readings were taken from");
        }
        List<FileUpload> sources = sourceUploadIds.stream().distinct().map(id -> {
            FileUpload file = uploadRepository.findByPublicId(id)
                    .orElseThrow(() -> new VitalsException("No such file"));
            if (Boolean.TRUE.equals(file.getDeleted())
                    || !appointment.getPublicId().equals(file.getReferenceId())
                    || (file.getCategory() != FileCategory.VITALS_EVIDENCE
                    && file.getCategory() != FileCategory.LABORATORY_RESULT)) {
                throw new VitalsException("That file is not attached to this appointment");
            }
            return file;
        }).toList();

        if (entry.systolic() == null || entry.diastolic() == null
                || entry.heartRate() == null || entry.temperature() == null) {
            throw new VitalsException(
                    "Enter at least blood pressure, pulse and temperature. If the file does "
                            + "not show them, raise an issue instead.");
        }
        validate(entry);

        String actor = CurrentUser.usernameOrSystem();
        LocalDateTime now = LocalDateTime.now();
        String files = String.join(", ", sources.stream().map(FileUpload::getOriginalFileName).toList());

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
        vitals.setMeasuredAt(entry.measuredAt() == null ? now : entry.measuredAt());
        vitals.setMeasurementSource(entry.measurementSource() == null || entry.measurementSource().isBlank()
                ? "Patient's uploaded record"
                : entry.measurementSource().strip());
        vitals.setNotes(truncate(("Transcribed by " + actor + " from " + files + "."
                + (entry.notes() == null || entry.notes().isBlank() ? "" : " " + entry.notes().strip())), 1000));
        vitals.setIsSelfReported(true);
        vitals.setNurseVerified(true);
        vitals.setVerifiedAt(now);
        vitals.setVerifiedBy(actor);
        vitals.setBmi(deriveBmi(entry.weightKg(), entry.heightCm()));

        Vitals saved = vitalsRepository.save(vitals);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("Vitals")
                .entityId(saved.getId())
                .details("Vitals transcribed for appointment " + appointment.getReference()
                        + " from " + files)
                .build());
        return saved;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
        // The flag existed and was never set, so it always said unverified.
        vitals.setNurseVerified(true);
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

    public void validate(VitalsEntry e) {
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
