package com.fnph.telepsychiatric.patient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.clinical.VitalsService;
import com.fnph.telepsychiatric.payment.PaymentRepository;
import com.fnph.telepsychiatric.scheduling.BookingService;
import com.fnph.telepsychiatric.scheduling.ScheduleAudience;
import com.fnph.telepsychiatric.triage.TriageService;
import com.fnph.telepsychiatric.upload.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor
public class PatientJourneyService {
    private final PatientRepository patients;
    private final PatientIntakeRepository intakes;
    private final PaymentRepository payments;
    private final AppointmentRepository appointments;
    private final FileUploadRepository files;
    private final VitalsService vitals;
    private final TriageService triage;
    private final BookingService booking;
    private final ObjectMapper mapper;

    public record Intake(String reason, String context, String mode, VitalsService.VitalsEntry vitals,
                         List<String> evidenceIds, List<String> laboratoryIds) {}
    public record Draft(String publicId, Intake intake) {}

    @Transactional(readOnly = true)
    public Intake forAppointment(String publicId) {
        var a = appointments.findByPublicId(publicId).orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("No such appointment"));
        var user = com.fnph.telepsychiatric.security.CurrentUser.require();
        boolean coordinator = user.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("appointment.approve"));
        boolean assigned = java.util.stream.Stream.of(a.getDoctor(), a.getNurse(), a.getHimOfficer(), a.getPharmacist(), a.getLaboratoryTechnician())
                .filter(java.util.Objects::nonNull).anyMatch(u -> u.getId().equals(user.getUserId()));
        if (user.getCentreId() != null || (!coordinator && !assigned))
            throw new jakarta.persistence.EntityNotFoundException("No such appointment");
        return intakes.findByAppointmentPublicId(publicId).map(this::decode).orElse(null);
    }

    @Transactional(readOnly = true)
    public Draft current(Long patientId) {
        return intakes.findFirstByPatientIdAndAppointmentPublicIdIsNullOrderByCreatedAtDesc(patientId)
                .map(i -> new Draft(i.getPublicId(), decode(i))).orElse(null);
    }

    @Transactional
    public Draft save(Long patientId, Intake input) {
        patients.findByIdForUpdate(patientId).orElseThrow(() -> new IllegalArgumentException("No patient account"));
        triage.requireClearedForBooking(patientId, "FNPH_PATIENT");
        validate(patientId, input);
        PatientIntake row = intakes.findFirstByPatientIdAndAppointmentPublicIdIsNullOrderByCreatedAtDesc(patientId)
                .orElseGet(PatientIntake::new);
        row.setPatientId(patientId);
        try { row.setPayload(mapper.writeValueAsString(input)); }
        catch (Exception e) { throw new IllegalArgumentException("Unable to save intake"); }
        intakes.saveAndFlush(row);
        return new Draft(row.getPublicId(), input);
    }

    @Transactional(readOnly = true)
    public void requireIntake(Long patientId) {
        triage.requireClearedForBooking(patientId, "FNPH_PATIENT");
        Draft draft = current(patientId);
        if (draft == null) throw new IllegalArgumentException("Complete your consultation information before payment");
        validate(patientId, draft.intake());
    }

    @Transactional(readOnly = true)
    public void requirePayment(Long patientId) {
        requireIntake(patientId);
        if (payments.findUnusedVerifiedPayments(patientId).isEmpty())
            throw new IllegalArgumentException("Verify the consultation payment before choosing a time");
    }

    @Transactional
    public Appointment submit(Long patientId, String intakeId, String slotId) {
        // Serializes retries and two tabs for the same patient. The slot itself is also locked by BookingService.
        Patient patient = patients.findByIdForUpdate(patientId)
                .orElseThrow(() -> new IllegalArgumentException("No patient account"));
        PatientIntake row = intakes.findByPublicIdAndPatientId(intakeId, patientId)
                .orElseThrow(() -> new IllegalArgumentException("No such intake"));
        if (row.getAppointmentPublicId() != null)
            return appointments.findByPublicId(row.getAppointmentPublicId()).orElseThrow();
        requirePayment(patientId);
        Intake input = decode(row);
        validate(patientId, input);
        var payment = payments.findUnusedVerifiedPayments(patientId).get(0);
        var bookingWindowEnd = java.time.LocalDate.now().plusMonths(3);
        boolean published = booking.bookableSlots(ScheduleAudience.FNPH_PATIENT).stream()
                .anyMatch(s -> s.getPublicId().equals(slotId)
                        && !s.getStartAt().toLocalDate().isAfter(bookingWindowEnd));
        if (!published) throw new IllegalArgumentException("That time is no longer available. Choose another time.");
        Appointment appointment = booking.holdSlot(patient, slotId);
        if (input.vitals() != null) vitals.record(appointment.getPublicId(), input.vitals());
        java.util.stream.Stream.concat(ids(input.evidenceIds()).stream(), ids(input.laboratoryIds()).stream())
                .forEach(id -> { var file = files.findByPublicId(id).orElseThrow();
                    file.setReferenceId(appointment.getPublicId()); files.save(file); });
        booking.confirmPayment(appointment, payment);
        payment.setAppointment(appointment);
        payments.save(payment);
        row.setAppointmentPublicId(appointment.getPublicId());
        intakes.save(row);
        return appointment;
    }

    private void validate(Long patientId, Intake input) {
        if (input == null || input.reason() == null || input.reason().isBlank() || input.reason().length() > 4000)
            throw new IllegalArgumentException("Describe your reason for consultation (up to 4,000 characters)");
        if (input.context() != null && input.context().length() > 4000)
            throw new IllegalArgumentException("Additional context must be no longer than 4,000 characters");
        if (!"VIDEO".equals(input.mode()) && !"AUDIO_FALLBACK".equals(input.mode()))
            throw new IllegalArgumentException("Choose video-first or audio fallback");
        if (input.vitals() == null && ids(input.evidenceIds()).isEmpty())
            throw new IllegalArgumentException("Provide recent measured vital signs or upload vital-sign evidence");
        if (input.vitals() != null) {
            var v = input.vitals();
            if (v.systolic() == null || v.diastolic() == null || v.heartRate() == null || v.temperature() == null
                    || v.measuredAt() == null || v.measurementSource() == null || v.measurementSource().isBlank())
                throw new IllegalArgumentException("Enter blood pressure, pulse, temperature, measurement time and source");
            vitals.validate(v);
        }
        validateFiles(patientId, ids(input.evidenceIds()), FileCategory.VITALS_EVIDENCE);
        validateFiles(patientId, ids(input.laboratoryIds()), FileCategory.LABORATORY_RESULT);
    }

    private void validateFiles(Long patientId, List<String> ids, FileCategory category) {
        if (ids.size() > 10) throw new IllegalArgumentException("A maximum of ten files can be attached");
        for (String id : ids) {
            var file = files.findByPublicId(id).orElseThrow(() -> new IllegalArgumentException("No such attachment"));
            if (file.getPatient() == null || !patientId.equals(file.getPatient().getId())
                    || Boolean.TRUE.equals(file.getDeleted()) || file.getCategory() != category
                    || file.getReferenceId() != null || file.getScanStatus() == ScanStatus.QUARANTINED
                    || file.getScanStatus() == ScanStatus.REJECTED)
                throw new IllegalArgumentException("Attachment is unavailable for this request");
        }
    }
    private List<String> ids(List<String> values) { return values == null ? List.of() : values; }
    private Intake decode(PatientIntake row) {
        try { return mapper.readValue(row.getPayload(), Intake.class); }
        catch (Exception e) { throw new IllegalStateException("Unable to read saved intake", e); }
    }
}
