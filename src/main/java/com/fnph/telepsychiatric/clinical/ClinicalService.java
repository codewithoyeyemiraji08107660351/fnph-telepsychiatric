package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.consultation.Consultation;
import com.fnph.telepsychiatric.consultation.ConsultationNote;
import com.fnph.telepsychiatric.consultation.ConsultationNoteRepository;
import com.fnph.telepsychiatric.consultation.ConsultationRepository;
import com.fnph.telepsychiatric.document.DocumentType;
import com.fnph.telepsychiatric.document.IssuedDocumentService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the doctor produces during and after a consultation.
 *
 * <h2>Signing is the point of no return</h2>
 *
 * An unsigned note is a draft and can be rewritten freely. A signed one cannot
 * be edited at all: an amendment is a new version pointing at what it replaced.
 * Editing in place destroys the one thing an investigation would want, which is
 * what the clinician wrote at the time.
 *
 * <h2>Issuing sends work forward, never back</h2>
 *
 * A prescription goes to the assigned pharmacist and an investigation request
 * to the assigned technician. Neither returns to the doctor through the system.
 * If a reviewer finds a problem it reaches the Hub Coordinator, and any change
 * is a new document the doctor authors.
 *
 * <h2>"None needed" is a decision that gets recorded</h2>
 *
 * Most consultations produce no investigation request. Saying so explicitly is
 * what stops the release bundle waiting forever for a document nobody intends
 * to write, and the reason is required because "no prescription" with no
 * explanation reads as an unfinished consultation a year later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClinicalService {

    private final ConsultationRepository consultationRepository;
    private final ConsultationNoteRepository noteRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;
    private final ReleaseService releaseService;
    private final ProfessionalReviewService reviewService;
    private final IssuedDocumentService documentService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Clinical note
    // -----------------------------------------------------------------

    @Transactional
    public ConsultationNote saveDraft(String consultationPublicId, String clinicalNote) {
        Consultation consultation = requireConsultation(consultationPublicId);

        ConsultationNote note = noteRepository
                .findFirstByConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseGet(() -> {
                    ConsultationNote created = new ConsultationNote();
                    created.setConsultation(consultation);
                    created.setVersion(1);
                    return created;
                });

        if (note.getSignedAt() != null) {
            throw new ClinicalException(
                    "That note is signed. Amend it instead, which creates a new version "
                            + "pointing at this one.");
        }
        note.setClinicalNote(clinicalNote);
        return noteRepository.save(note);
    }

    /**
     * Signs the note. Irreversible.
     *
     * Signing also opens the release bundle and marks the note component
     * complete, which is what starts the completeness check the Hub Coordinator
     * later acts on.
     */
    @Transactional
    public ConsultationNote sign(String consultationPublicId, String followUpRecommendation,
                                 String followUpTimeline) {
        Consultation consultation = requireConsultation(consultationPublicId);

        ConsultationNote note = noteRepository
                .findFirstByConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseThrow(() -> new ClinicalException("There is no note to sign"));

        if (note.getSignedAt() != null) {
            return note;
        }
        if (note.getClinicalNote() == null || note.getClinicalNote().isBlank()) {
            throw new ClinicalException("An empty note cannot be signed");
        }

        note.setSignedAt(LocalDateTime.now());
        note.setSignedBy(CurrentUser.usernameOrSystem());
        note.setFollowUpRecommendation(followUpRecommendation);
        note.setFollowUpTimeline(followUpTimeline);
        noteRepository.save(note);

        Appointment appointment = consultation.getAppointment();
        if (appointment != null) {
            ReleaseBundle bundle = releaseService.openFor(appointment);
            releaseService.markComponentComplete(bundle, ComponentType.CLINICAL_NOTE);
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CLINICAL_NOTE_SIGNED)
                .entityType("ConsultationNote")
                .entityId(note.getId())
                .details("Version %d signed".formatted(note.getVersion()))
                .build());

        log.info("Clinical note {} version {} signed", note.getPublicId(), note.getVersion());
        return note;
    }

    /**
     * Amends a signed note by superseding it.
     *
     * The old version stays and is marked superseded, so the record shows what
     * was written first and what replaced it, and why.
     */
    @Transactional
    public ConsultationNote amend(String consultationPublicId, String clinicalNote,
                                  String amendmentReason) {
        if (amendmentReason == null || amendmentReason.isBlank()) {
            throw new ClinicalException(
                    "Say why the note is being amended. The previous version stays in the "
                            + "record and the reason is what explains the difference.");
        }
        Consultation consultation = requireConsultation(consultationPublicId);

        ConsultationNote current = noteRepository
                .findFirstByConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseThrow(() -> new ClinicalException("There is no note to amend"));

        if (current.getSignedAt() == null) {
            throw new ClinicalException("That note is unsigned. Edit it directly.");
        }

        current.setSupersededAt(LocalDateTime.now());
        noteRepository.save(current);

        ConsultationNote amended = new ConsultationNote();
        amended.setConsultation(consultation);
        amended.setClinicalNote(clinicalNote);
        amended.setVersion(current.getVersion() + 1);
        amended.setSupersedesId(current.getId());
        amended.setAmendmentReason(amendmentReason);
        amended.setFollowUpRecommendation(current.getFollowUpRecommendation());
        amended.setFollowUpTimeline(current.getFollowUpTimeline());
        amended.setSignedAt(LocalDateTime.now());
        amended.setSignedBy(CurrentUser.usernameOrSystem());
        ConsultationNote saved = noteRepository.save(amended);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CLINICAL_NOTE_SIGNED)
                .entityType("ConsultationNote")
                .entityId(saved.getId())
                .details("Version %d supersedes version %d"
                        .formatted(saved.getVersion(), current.getVersion()))
                .reason(amendmentReason)
                .build());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<ConsultationNote> noteHistory(String consultationPublicId) {
        return noteRepository.findAllByConsultationIdOrderByVersionAsc(
                requireConsultation(consultationPublicId).getId());
    }

    // -----------------------------------------------------------------
    // Prescription
    // -----------------------------------------------------------------

    @Transactional
    public Prescription issuePrescription(String consultationPublicId, String clinicalInformation,
                                          List<PrescriptionLine> lines) {
        Consultation consultation = requireConsultation(consultationPublicId);

        if (lines == null || lines.isEmpty()) {
            throw new ClinicalException(
                    "A prescription with no items is not a prescription. If none is needed, "
                            + "record that instead.");
        }

        Appointment appointment = consultation.getAppointment();
        ReleaseBundle bundle = appointment == null ? null : releaseService.openFor(appointment);

        Prescription prescription = new Prescription();
        prescription.setConsultation(consultation);
        prescription.setPatient(consultation.getPatient());
        prescription.setDoctor(consultation.getDoctor());
        prescription.setBundle(bundle);
        prescription.setClinicalInformation(clinicalInformation);
        prescription.setIssueDate(LocalDate.now());
        prescription.setStatus(ClinicalDocumentStatus.PENDING_REVIEW);
        Prescription saved = prescriptionRepository.save(prescription);

        lines.forEach(line -> {
            PrescriptionItem item = new PrescriptionItem();
            item.setPrescription(saved);
            item.setMedication(line.medication());
            item.setStrength(line.strength());
            item.setFrequency(line.frequency());
            item.setDuration(line.duration());
            item.setInstructions(line.instructions());
            saved.getItems().add(item);
        });
        prescriptionRepository.save(saved);

        // Forward to the pharmacist the Hub Coordinator named at approval, not
        // into a shared pool anyone can pick from.
        reviewService.assignForPrescription(saved,
                appointment == null ? null : appointment.getPharmacist());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PRESCRIPTION_ISSUED)
                .entityType("Prescription")
                .entityId(saved.getId())
                .details("%d item(s), sent for pharmacy review".formatted(lines.size()))
                .build());

        log.info("Prescription {} issued with {} item(s)", saved.getPublicId(), lines.size());
        return saved;
    }

    /**
     * Issues a corrected prescription that supersedes an earlier one.
     *
     * The only route to changing a prescription. There is no edit, because a
     * prescription changeable by anyone other than the prescriber is not a
     * prescription.
     */
    @Transactional
    public Prescription supersedePrescription(String prescriptionPublicId, String reason,
                                              String clinicalInformation,
                                              List<PrescriptionLine> lines) {
        Prescription previous = prescriptionRepository.findByPublicId(prescriptionPublicId)
                .orElseThrow(() -> new ClinicalException("No such prescription"));

        if (reason == null || reason.isBlank()) {
            throw new ClinicalException("Say why this prescription is being replaced");
        }

        Prescription replacement = issuePrescription(
                previous.getConsultation().getPublicId(), clinicalInformation, lines);
        replacement.setSupersedesId(previous.getId());
        prescriptionRepository.save(replacement);

        previous.setStatus(ClinicalDocumentStatus.SUPERSEDED);
        prescriptionRepository.save(previous);

        // A copy the patient already holds must stop being valid at a pharmacy
        // counter. Silent if the bundle was never released, which is normal for
        // a correction made the same day.
        documentService.revokeForSource(DocumentType.PRESCRIPTION, previous.getId(),
                "Superseded by a corrected prescription: " + reason);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PRESCRIPTION_SUPERSEDED)
                .entityType("Prescription")
                .entityId(previous.getId())
                .details("Replaced by " + replacement.getPublicId())
                .reason(reason)
                .build());

        return replacement;
    }

    // -----------------------------------------------------------------
    // Investigation
    // -----------------------------------------------------------------

    @Transactional
    public Investigation issueInvestigation(String consultationPublicId,
                                            String clinicalInformation,
                                            List<InvestigationLine> lines) {
        Consultation consultation = requireConsultation(consultationPublicId);

        if (lines == null || lines.isEmpty()) {
            throw new ClinicalException(
                    "An investigation request with no panels is not a request. If none is "
                            + "needed, record that instead.");
        }

        Appointment appointment = consultation.getAppointment();
        ReleaseBundle bundle = appointment == null ? null : releaseService.openFor(appointment);

        Investigation investigation = new Investigation();
        investigation.setConsultation(consultation);
        investigation.setPatient(consultation.getPatient());
        investigation.setDoctor(consultation.getDoctor());
        investigation.setBundle(bundle);
        investigation.setClinicalInformation(clinicalInformation);
        investigation.setIssueDate(LocalDate.now());
        investigation.setStatus(ClinicalDocumentStatus.PENDING_REVIEW);
        Investigation saved = investigationRepository.save(investigation);

        lines.forEach(line -> {
            InvestigationItem item = new InvestigationItem();
            item.setInvestigation(saved);
            item.setPanelName(line.panelName());
            item.setPanelCode(line.panelCode());
            item.setNotes(line.notes());
            saved.getItems().add(item);
        });
        investigationRepository.save(saved);

        reviewService.assignForInvestigation(saved,
                appointment == null ? null : appointment.getLaboratoryTechnician());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.INVESTIGATION_ISSUED)
                .entityType("Investigation")
                .entityId(saved.getId())
                .details("%d panel(s), sent for laboratory review".formatted(lines.size()))
                .build());

        return saved;
    }

    // -----------------------------------------------------------------
    // Follow-up and "none needed"
    // -----------------------------------------------------------------

    @Transactional
    public FollowUp recordFollowUp(String consultationPublicId, String recommendation,
                                   String reviewInterval, LocalDate preferredDate) {
        Consultation consultation = requireConsultation(consultationPublicId);
        Appointment appointment = consultation.getAppointment();
        ReleaseBundle bundle = appointment == null ? null : releaseService.openFor(appointment);

        FollowUp followUp = new FollowUp();
        followUp.setConsultation(consultation);
        followUp.setPatient(consultation.getPatient());
        followUp.setBundle(bundle);
        followUp.setRecommendation(recommendation);
        followUp.setReviewInterval(reviewInterval);
        followUp.setPreferredDate(preferredDate);
        FollowUp saved = followUpRepository.save(followUp);

        releaseService.markComponentComplete(bundle, ComponentType.FOLLOW_UP);
        return saved;
    }

    /**
     * Records that a component is deliberately not needed.
     *
     * The alternative is a bundle blocked forever waiting for a document nobody
     * intends to write, which the coordinator would eventually release by
     * guessing.
     */
    @Transactional
    public void markNotRequired(String consultationPublicId, ComponentType component,
                                String reason) {
        Consultation consultation = requireConsultation(consultationPublicId);
        Appointment appointment = consultation.getAppointment();

        if (appointment == null) {
            throw new ClinicalException("That consultation has no appointment");
        }
        releaseService.markComponentNotRequired(
                releaseService.openFor(appointment), component, reason);

        log.info("{} recorded as not required for consultation {}: {}",
                component, consultation.getPublicId(), reason);
    }

    // -----------------------------------------------------------------

    private Consultation requireConsultation(String publicId) {
        Consultation consultation = consultationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ClinicalException("No such consultation"));

        // Only the consulting clinician authors on their own consultation.
        CurrentUser.get().ifPresent(principal -> {
            if (consultation.getDoctor() != null
                    && !consultation.getDoctor().getId().equals(principal.getUserId())) {
                throw new ClinicalException(
                        "That consultation belongs to another clinician");
            }
        });
        return consultation;
    }

    public record PrescriptionLine(String medication, String strength, String frequency,
                                   String duration, String instructions) {
    }

    public record InvestigationLine(String panelName, String panelCode, String notes) {
    }

    public static class ClinicalException extends RuntimeException {
        public ClinicalException(String message) {
            super(message);
        }
    }
}
