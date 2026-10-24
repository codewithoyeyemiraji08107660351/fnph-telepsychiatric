package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.clinical.FollowUpRepository;
import com.fnph.telepsychiatric.clinical.InvestigationRepository;
import com.fnph.telepsychiatric.clinical.PrescriptionRepository;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.consultation.CentreConsultationNote;
import com.fnph.telepsychiatric.consultation.CentreConsultationNoteRepository;
import com.fnph.telepsychiatric.consultation.CentreConsultationRepository;
import com.fnph.telepsychiatric.consultation.ConsultationNote;
import com.fnph.telepsychiatric.consultation.ConsultationNoteRepository;
import com.fnph.telepsychiatric.consultation.ConsultationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only clinical contents of a release bundle, for either pathway.
 *
 * Moved out of ReleaseController so the centre can read a delivered bundle
 * through the same code the Hub Coordinator released it from. Two copies would
 * drift, and the centre would then be dispensing from something other than
 * what FNPH checked.
 *
 * The note lookup differs by pathway: an FNPH note hangs off the hospital
 * consultation, a centre note off the centre consultation. Previously only the
 * FNPH path was followed, so a centre bundle showed no note at all.
 */
@Component
@RequiredArgsConstructor
public class BundleContentAssembler {

    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationNoteRepository consultationNoteRepository;
    private final CentreConsultationRepository centreConsultationRepository;
    private final CentreConsultationNoteRepository centreConsultationNoteRepository;

    @Transactional(readOnly = true)
    public ReleaseBundleResponse.ClinicalContent contentOf(ReleaseBundle bundle) {
        var prescriptions = prescriptionRepository.findAllByBundleId(bundle.getId()).stream()
                .map(p -> new ReleaseBundleResponse.PrescriptionContent(
                        p.getPublicId(),
                        p.getIssueNumber(),
                        p.getStatus() == null ? null : p.getStatus().name(),
                        Boolean.TRUE.equals(p.getNotRequired()),
                        p.getNotRequiredReason(),
                        p.getIssueDate(),
                        p.getExpiryDate(),
                        p.getValidityDays(),
                        p.getClinicalInformation(),
                        p.getItems().stream()
                                .map(item -> new ReleaseBundleResponse.PrescriptionItemContent(
                                        item.getMedication(),
                                        item.getStrength(),
                                        item.getFrequency(),
                                        item.getDuration(),
                                        item.getInstructions(),
                                        item.getSequence()))
                                .toList()))
                .toList();

        var investigations = investigationRepository.findAllByBundleId(bundle.getId()).stream()
                .map(i -> new ReleaseBundleResponse.InvestigationContent(
                        i.getPublicId(),
                        i.getIssueNumber(),
                        i.getStatus() == null ? null : i.getStatus().name(),
                        Boolean.TRUE.equals(i.getNotRequired()),
                        i.getNotRequiredReason(),
                        i.getClinicalInformation(),
                        i.getIssueDate(),
                        i.getExpiryDate(),
                        i.getValidityDays(),
                        i.getItems().stream()
                                .map(item -> new ReleaseBundleResponse.InvestigationItemContent(
                                        item.getPanelName(),
                                        item.getPanelCode(),
                                        item.getNotes(),
                                        item.getSequence()))
                                .toList()))
                .toList();

        var followUps = followUpRepository.findAllByBundleId(bundle.getId()).stream()
                .map(f -> new ReleaseBundleResponse.FollowUpContent(
                        f.getPublicId(),
                        f.getRecommendation(),
                        f.getReviewInterval(),
                        f.getExpectedTimeframe(),
                        f.getPreferredDate(),
                        f.getPreferredTime(),
                        f.getConsultationMode() == null ? null : f.getConsultationMode().name(),
                        f.getStatus() == null ? null : f.getStatus().name(),
                        f.getScheduledDate(),
                        f.getCompletedDate(),
                        f.getNotes()))
                .toList();

        return new ReleaseBundleResponse.ClinicalContent(
                noteOf(bundle), prescriptions, investigations, followUps);
    }

    private ReleaseBundleResponse.ClinicalNoteContent noteOf(ReleaseBundle bundle) {
        if (bundle.getAppointment() != null) {
            return consultationRepository.findByAppointmentId(bundle.getAppointment().getId())
                    .flatMap(c -> consultationNoteRepository
                            .findFirstByConsultationIdAndSupersededAtIsNullOrderByVersionDesc(c.getId()))
                    .map(BundleContentAssembler::fnphNote)
                    .orElse(null);
        }
        if (bundle.getCentreAppointment() != null) {
            return centreConsultationRepository.findByCentreAppointmentId(bundle.getCentreAppointment().getId())
                    .flatMap(c -> centreConsultationNoteRepository
                            .findFirstByCentreConsultationIdAndSupersededAtIsNullOrderByVersionDesc(c.getId()))
                    .map(BundleContentAssembler::centreNote)
                    .orElse(null);
        }
        return null;
    }

    private static ReleaseBundleResponse.ClinicalNoteContent fnphNote(ConsultationNote note) {
        return new ReleaseBundleResponse.ClinicalNoteContent(
                note.getClinicalNote(),
                note.getVersion(),
                Boolean.TRUE.equals(note.getIsAuthoritative()),
                Boolean.TRUE.equals(note.getIsSigned()),
                note.getSignedAt(),
                note.getSignedBy(),
                note.getFollowUpRecommendation(),
                note.getFollowUpTimeline());
    }

    /** A centre note has no authoritative flag; the unsuperseded version is the one in force. */
    private static ReleaseBundleResponse.ClinicalNoteContent centreNote(CentreConsultationNote note) {
        return new ReleaseBundleResponse.ClinicalNoteContent(
                note.getClinicalNote(),
                note.getVersion(),
                note.getSupersededAt() == null,
                Boolean.TRUE.equals(note.getIsSigned()),
                note.getSignedAt(),
                note.getSignedBy(),
                note.getFollowUpRecommendation(),
                note.getFollowUpTimeline());
    }
}
