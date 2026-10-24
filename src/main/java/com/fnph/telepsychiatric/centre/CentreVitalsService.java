package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.clinical.CentreVitals;
import com.fnph.telepsychiatric.clinical.CentreVitalsRepository;
import com.fnph.telepsychiatric.clinical.VitalsService;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Readings centre staff take before the consultation.
 *
 * The table and entity existed, but nothing wrote to them: centre staff could
 * only photograph the readings and upload the picture, and the doctor read
 * numbers off an image. Same plausibility rules as FNPH nursing, so a slipped
 * decimal point is caught here as it is there.
 */
@Service
@RequiredArgsConstructor
public class CentreVitalsService {

    private static final Set<Status> OPEN = EnumSet.of(
            Status.AWAITING_APPROVAL, Status.APPROVED, Status.IN_PROGRESS);

    private final CentreAppointmentRepository appointmentRepository;
    private final CentreVitalsRepository vitalsRepository;
    private final VitalsService vitalsService;
    private final AuditService auditService;

    @Transactional
    public CentreVitals record(String appointmentPublicId, VitalsService.VitalsEntry entry) {
        CentreAppointment appointment = appointmentRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), appointmentPublicId)
                .orElseThrow(() -> new VitalsService.VitalsException("No such consultation"));
        if (!OPEN.contains(appointment.getStatus())) {
            throw new VitalsService.VitalsException(
                    "Vitals are recorded for a requested, confirmed or running consultation. This one is "
                            + appointment.getStatus() + ".");
        }
        if (entry.systolic() == null && entry.diastolic() == null && entry.heartRate() == null
                && entry.respiratoryRate() == null && entry.temperature() == null && entry.weightKg() == null
                && entry.heightCm() == null && entry.bloodOxygen() == null && entry.bloodGlucose() == null) {
            throw new VitalsService.VitalsException("Enter at least one reading.");
        }
        vitalsService.validate(entry);

        CentreVitals vitals = new CentreVitals();
        vitals.setCentre(appointment.getCentre());
        vitals.setCentrePatient(appointment.getCentrePatient());
        vitals.setCentreAppointment(appointment);
        vitals.setBloodPressureSystolic(entry.systolic());
        vitals.setBloodPressureDiastolic(entry.diastolic());
        vitals.setHeartRate(entry.heartRate());
        vitals.setRespiratoryRate(entry.respiratoryRate());
        vitals.setTemperature(entry.temperature());
        vitals.setWeightKg(entry.weightKg());
        vitals.setHeightCm(entry.heightCm());
        vitals.setBmi(bmi(entry.weightKg(), entry.heightCm()));
        vitals.setBloodOxygen(entry.bloodOxygen());
        vitals.setBloodGlucose(entry.bloodGlucose());
        vitals.setMeasuredAt(entry.measuredAt() == null ? LocalDateTime.now() : entry.measuredAt());
        vitals.setMeasurementSource("CENTRE_STAFF");
        vitals.setIsSelfReported(false);
        vitals.setNotes(entry.notes());
        CentreVitals saved = vitalsRepository.save(vitals);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("CentreVitals")
                .entityId(saved.getId())
                .details("Vitals recorded for " + appointment.getReference())
                .build());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CentreVitals> forOwnAppointment(String appointmentPublicId) {
        Long centreId = TenantContext.requireCentreId();
        CentreAppointment appointment = appointmentRepository
                .findByCentreIdAndPublicId(centreId, appointmentPublicId)
                .orElseThrow(() -> new VitalsService.VitalsException("No such consultation"));
        return vitalsRepository.findAllByCentreIdAndCentreAppointmentIdOrderByMeasuredAtDesc(
                centreId, appointment.getId());
    }

    /** Same rule as FNPH: computed from height and weight, never taken from the client. */
    static Double bmi(Double weightKg, Double heightCm) {
        if (weightKg == null || heightCm == null || heightCm <= 0) {
            return null;
        }
        double metres = heightCm / 100.0;
        return Math.round((weightKg / (metres * metres)) * 10.0) / 10.0;
    }
}
