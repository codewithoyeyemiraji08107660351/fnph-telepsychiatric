package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.consultation.CentreConsultation;
import com.fnph.telepsychiatric.consultation.CentreConsultationNote;
import com.fnph.telepsychiatric.consultation.CentreConsultationNoteRepository;
import com.fnph.telepsychiatric.consultation.CentreConsultationRepository;
import com.fnph.telepsychiatric.document.DocumentType;
import com.fnph.telepsychiatric.document.IssuedDocumentService;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Clinical authoring on the centre pathway.
 *
 * <h2>Why this is a separate service</h2>
 *
 * On the FNPH pathway the offline EHR is the authoritative record and the
 * online note is optional. On the centre pathway the online note <b>is</b> the
 * authoritative record, and Module 3 states the doctor may complete it after
 * the call. So nothing here may depend on the session still being open, which
 * is the one behavioural difference from {@link ClinicalService}.
 *
 * <h2>A centre patient is never an FNPH patient</h2>
 *
 * Every output here is bound to {@code centrePatient} and {@code centre}, never
 * to {@code patient}. A referral narrative may mention an FNPH EHR number and
 * that changes nothing: the offline FNPH record is not linked or retrieved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreClinicalService {

    private final CentreConsultationRepository consultationRepository;
    private final CentreConsultationNoteRepository noteRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;
    private final ReleaseService releaseService;
    private final ProfessionalReviewService reviewService;
    private final IssuedDocumentService documentService;
    private final AuditService auditService;
    private final InvestigationNumberService investigationNumberService; 
    private final PrescriptionNumberService prescriptionNumberService;

    // -----------------------------------------------------------------
    // Clinical note
    // -----------------------------------------------------------------

    @Transactional
    public CentreConsultationNote saveDraft(String consultationPublicId, String clinicalNote) {
        CentreConsultation consultation = requireConsultation(consultationPublicId);

        CentreConsultationNote note = noteRepository
                .findFirstByCentreConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseGet(() -> {
                    CentreConsultationNote created = new CentreConsultationNote();
                    created.setCentreConsultation(consultation);
                    created.setCentre(consultation.getCentre());
                    created.setVersion(1);
                    return created;
                });

        if (note.getSignedAt() != null) {
            throw new ClinicalService.ClinicalException(
                    "That note is signed. Amend it instead, which creates a new version "
                            + "pointing at this one.");
        }
        note.setClinicalNote(clinicalNote);
        return noteRepository.save(note);
    }

    /**
     * Signs the note and opens the bundle.
     *
     * Deliberately does not require the consultation to be closed. Module 3
     * allows the doctor to complete the record after the call.
     */
    @Transactional
    public CentreConsultationNote sign(String consultationPublicId,
                                       String followUpRecommendation,
                                       String followUpTimeline) {
        CentreConsultation consultation = requireConsultation(consultationPublicId);

        CentreConsultationNote note = noteRepository
                .findFirstByCentreConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseThrow(() -> new ClinicalService.ClinicalException(
                        "There is no note to sign"));

        if (note.getSignedAt() != null) {
            return note;
        }
        if (note.getClinicalNote() == null || note.getClinicalNote().isBlank()) {
            throw new ClinicalService.ClinicalException("An empty note cannot be signed");
        }

        note.setSignedAt(LocalDateTime.now());
        note.setSignedBy(CurrentUser.usernameOrSystem());
        note.setIsSigned(true);
        note.setFollowUpRecommendation(followUpRecommendation);
        note.setFollowUpTimeline(followUpTimeline);
        noteRepository.save(note);

        CentreAppointment appointment = consultation.getCentreAppointment();
        if (appointment != null) {
            ReleaseBundle bundle = releaseService.openFor(appointment);
            releaseService.markComponentComplete(bundle, ComponentType.CLINICAL_NOTE);
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CLINICAL_NOTE_SIGNED)
                .entityType("CentreConsultationNote")
                .entityId(note.getId())
                .details("Version %d signed".formatted(note.getVersion()))
                .build());

        log.info("Centre clinical note {} version {} signed",
                note.getPublicId(), note.getVersion());
        return note;
    }

    /**
     * Amends a signed note by superseding it.
     *
     * The old version stays, marked superseded, so the record shows what was
     * written first, what replaced it and why. V14 created these columns for
     * both note tables and they were mapped on only one, which left the
     * pathway whose note is authoritative without an amendment trail.
     */
    @Transactional
    public CentreConsultationNote amend(String consultationPublicId, String clinicalNote,
                                        String amendmentReason) {
        if (amendmentReason == null || amendmentReason.isBlank()) {
            throw new ClinicalService.ClinicalException(
                    "Say why the note is being amended. The previous version stays in the "
                            + "record and the reason is what explains the difference.");
        }
        CentreConsultation consultation = requireConsultation(consultationPublicId);

        CentreConsultationNote current = noteRepository
                .findFirstByCentreConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
                        consultation.getId())
                .orElseThrow(() -> new ClinicalService.ClinicalException(
                        "There is no note to amend"));

        if (current.getSignedAt() == null) {
            throw new ClinicalService.ClinicalException(
                    "That note is unsigned. Edit it directly.");
        }

        LocalDateTime now = LocalDateTime.now();
        current.setSupersededAt(now);
        noteRepository.save(current);

        CentreConsultationNote amended = new CentreConsultationNote();
        amended.setCentreConsultation(consultation);
        amended.setCentre(consultation.getCentre());
        amended.setVersion(current.getVersion() + 1);
        amended.setSupersedesId(Math.toIntExact(current.getId()));
        amended.setClinicalNote(clinicalNote);
        amended.setAmendmentReason(amendmentReason);
        amended.setFollowUpRecommendation(current.getFollowUpRecommendation());
        amended.setFollowUpTimeline(current.getFollowUpTimeline());
        amended.setSignedAt(now);
        amended.setSignedBy(CurrentUser.usernameOrSystem());
        amended.setIsSigned(true);
        CentreConsultationNote saved = noteRepository.save(amended);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CLINICAL_NOTE_SIGNED)
                .entityType("CentreConsultationNote")
                .entityId(saved.getId())
                .details("Version %d supersedes version %d"
                        .formatted(saved.getVersion(), current.getVersion()))
                .reason(amendmentReason)
                .build());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<CentreConsultationNote> noteHistory(String consultationPublicId) {
        return noteRepository.findAllByCentreConsultationIdOrderByVersionDesc(
                requireConsultation(consultationPublicId).getId());
    }

    // -----------------------------------------------------------------
    // Prescription
    // -----------------------------------------------------------------

    /**
     * Same review routing as the FNPH pathway: forward to the pharmacist the
     * Hub Coordinator named at approval, never back to the doctor.
     *
     * The centre's own optional local pharmacy role does not review. Module 3
     * assigns Pharmacy and Laboratory staff at FNPH approval, and the local
     * roles act on the released bundle afterwards.
     */
    @Transactional
    public Prescription issuePrescription(String consultationPublicId,
                                          String clinicalInformation,
                                          List<ClinicalService.PrescriptionLine> lines) {
        CentreConsultation consultation = requireConsultation(consultationPublicId);

        if (lines == null || lines.isEmpty()) {
            throw new ClinicalService.ClinicalException(
                    "A prescription with no items is not a prescription. If none is needed, "
                            + "record that instead.");
        }

        CentreAppointment appointment = consultation.getCentreAppointment();
        ReleaseBundle bundle = appointment == null ? null : releaseService.openFor(appointment);

        Prescription prescription = new Prescription();
        prescription.setCentreConsultation(consultation);
        prescription.setCentrePatient(consultation.getCentrePatient());
        prescription.setCentre(consultation.getCentre());
        prescription.setDoctor(consultation.getDoctor());
        prescription.setBundle(bundle);
        prescription.setClinicalInformation(clinicalInformation);
        prescription.setIssueDate(LocalDate.now());
        prescription.setIssueNumber(prescriptionNumberService.next());
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

        reviewService.assignForPrescription(saved,
                appointment == null ? null : appointment.getPharmacy());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PRESCRIPTION_ISSUED)
                .entityType("Prescription")
                .entityId(saved.getId())
                .details("%d item(s), sent for pharmacy review".formatted(lines.size()))
                .build());

        log.info("Centre prescription {} issued with {} item(s)",
                saved.getPublicId(), lines.size());
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
                                              List<ClinicalService.PrescriptionLine> lines) {
        Prescription previous = prescriptionRepository.findByPublicId(prescriptionPublicId)
                .orElseThrow(() -> new ClinicalService.ClinicalException(
                        "No such prescription"));

        if (reason == null || reason.isBlank()) {
            throw new ClinicalService.ClinicalException(
                    "Say why this prescription is being replaced");
        }
        if (previous.getCentreConsultation() == null) {
            throw new ClinicalService.ClinicalException(
                    "That prescription is on the FNPH pathway. Use ClinicalService.");
        }

        Prescription replacement = issuePrescription(
                previous.getCentreConsultation().getPublicId(), clinicalInformation, lines);
        replacement.setSupersedesId(previous.getId());
        prescriptionRepository.save(replacement);

        previous.setStatus(ClinicalDocumentStatus.SUPERSEDED);
        prescriptionRepository.save(previous);

        // A copy the centre already holds must stop verifying at a pharmacy
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
                                            List<ClinicalService.InvestigationLine> lines) {
        CentreConsultation consultation = requireConsultation(consultationPublicId);

        if (lines == null || lines.isEmpty()) {
            throw new ClinicalService.ClinicalException(
                    "An investigation request with no panels is not a request. If none is "
                            + "needed, record that instead.");
        }

        CentreAppointment appointment = consultation.getCentreAppointment();
        ReleaseBundle bundle = appointment == null ? null : releaseService.openFor(appointment);

        Investigation investigation = new Investigation();
        investigation.setCentreConsultation(consultation);
        investigation.setCentrePatient(consultation.getCentrePatient());
        investigation.setCentre(consultation.getCentre());
        investigation.setDoctor(consultation.getDoctor());
        investigation.setBundle(bundle);
        investigation.setClinicalInformation(clinicalInformation);
        investigation.setIssueDate(LocalDate.now());
        investigation.setIssueNumber(investigationNumberService.next());
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
                appointment == null ? null : appointment.getLaboratory());

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

    /**
     * Records the recommendation and nothing more.
     *
     * Module 3 is explicit that a recommendation does not consume a slot:
     * centre and patient availability and the FNPH schedule still govern
     * booking. The centre dashboard shows it with the expected timeframe.
     */
    @Transactional
    public FollowUp recordFollowUp(String consultationPublicId, String recommendation,
                                   String reviewInterval, LocalDate preferredDate) {
        CentreConsultation consultation = requireConsultation(consultationPublicId);
        CentreAppointment appointment = consultation.getCentreAppointment();

        if (appointment == null) {
            throw new ClinicalService.ClinicalException(
                    "That consultation has no appointment");
        }
        ReleaseBundle bundle = releaseService.openFor(appointment);

        FollowUp followUp = new FollowUp();
        followUp.setCentreConsultation(consultation);
        followUp.setCentrePatient(consultation.getCentrePatient());
        followUp.setCentre(consultation.getCentre());
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
        CentreConsultation consultation = requireConsultation(consultationPublicId);
        CentreAppointment appointment = consultation.getCentreAppointment();

        if (appointment == null) {
            throw new ClinicalService.ClinicalException(
                    "That consultation has no appointment");
        }
        releaseService.markComponentNotRequired(
                releaseService.openFor(appointment), component, reason);

        log.info("{} recorded as not required for centre consultation {}: {}",
                component, consultation.getPublicId(), reason);
    }

    // -----------------------------------------------------------------

    /**
     * Resolves the consultation and confirms the caller is its clinician.
     *
     * Same rule as the FNPH pathway. It matters more here: the centre note is
     * the authoritative clinical record, so another clinician writing into it
     * would be altering the record itself rather than a copy of one.
     */
    private CentreConsultation requireConsultation(String publicId) {
        CentreConsultation consultation = consultationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ClinicalService.ClinicalException(
                        "No such consultation"));

        CurrentUser.get().ifPresent(principal -> {
            if (consultation.getDoctor() != null
                    && !consultation.getDoctor().getId().equals(principal.getUserId())) {
                throw new ClinicalService.ClinicalException(
                        "That consultation belongs to another clinician");
            }
        });
        return consultation;
    }
}