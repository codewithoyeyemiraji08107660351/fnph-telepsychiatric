package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.clinical.CentreClinicalService;
import com.fnph.telepsychiatric.clinical.ClinicalService;
import com.fnph.telepsychiatric.clinical.ComponentType;
import com.fnph.telepsychiatric.clinical.FollowUpRepository;
import com.fnph.telepsychiatric.clinical.Investigation;
import com.fnph.telepsychiatric.clinical.InvestigationRepository;
import com.fnph.telepsychiatric.clinical.Prescription;
import com.fnph.telepsychiatric.clinical.PrescriptionRepository;
import com.fnph.telepsychiatric.clinical.ReleaseBundleRepository;
import com.fnph.telepsychiatric.clinical.ReleaseService;
import com.fnph.telepsychiatric.consultation.CentreConsultationNote;
import com.fnph.telepsychiatric.consultation.CentreConsultationRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clinical authoring for centre consultations.
 *
 * CentreClinicalService was complete and had no controller, so a doctor on the
 * centre pathway could not write, sign or amend a note, issue a prescription or
 * investigation, record a follow-up, or waive a component. The centre's bundle
 * could therefore never settle, and nothing ever reached the centre's incoming
 * list. These endpoints mirror the FNPH ones under a centre prefix; the
 * service checks that the caller is the consultation's doctor.
 */
@RestController
@RequestMapping("/api/v1/clinical/centre-consultations")
@RequiredArgsConstructor
@Tag(name = "Centre Clinical Records")
public class CentreClinicalController {

    private final CentreClinicalService clinicalService;
    private final CentreConsultationRepository consultationRepository;
    private final CentreAppointmentRepository appointmentRepository;
    private final ReleaseBundleRepository releaseBundleRepository;
    private final ReleaseService releaseService;
    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;

    // ------------------------------------------------------------------
    // Authoring
    // ------------------------------------------------------------------

    @PutMapping("/{consultationPublicId}/note")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_WRITE)")
    @Operation(summary = "Save the draft note for a centre consultation")
    public ResponseEntity<ClinicalDtos.NoteResponse> saveNote(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.NoteRequest request) {
        return ResponseEntity.ok(toResponse(clinicalService.saveDraft(consultationPublicId, request.clinicalNote())));
    }

    @PostMapping("/{consultationPublicId}/note/sign")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(summary = "Sign the note for a centre consultation")
    public ResponseEntity<ClinicalDtos.NoteResponse> sign(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.SignNoteRequest request) {
        return ResponseEntity.ok(toResponse(clinicalService.sign(consultationPublicId,
                request.followUpRecommendation(), request.followUpTimeline())));
    }

    @PostMapping("/{consultationPublicId}/note/amend")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(summary = "Amend a signed centre consultation note")
    public ResponseEntity<ClinicalDtos.NoteResponse> amend(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.AmendNoteRequest request) {
        return ResponseEntity.ok(toResponse(clinicalService.amend(
                consultationPublicId, request.clinicalNote(), request.amendmentReason())));
    }

    @GetMapping("/{consultationPublicId}/note/history")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_READ)")
    @Transactional(readOnly = true)
    @Operation(summary = "Every version of a centre consultation note")
    public ResponseEntity<List<ClinicalDtos.NoteResponse>> noteHistory(@PathVariable String consultationPublicId) {
        return ResponseEntity.ok(clinicalService.noteHistory(consultationPublicId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/{consultationPublicId}/prescriptions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_WRITE)")
    @Transactional
    @Operation(summary = "Issue a prescription from a centre consultation")
    public ResponseEntity<ClinicalDtos.DocumentResponse> issuePrescription(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.IssuePrescriptionRequest request) {
        Prescription prescription = clinicalService.issuePrescription(consultationPublicId,
                request.clinicalInformation(), prescriptionLines(request.items()));
        return ResponseEntity.status(HttpStatus.CREATED).body(documentResponse(prescription));
    }

    @PostMapping("/{consultationPublicId}/prescriptions/{prescriptionPublicId}/supersede")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_WRITE)")
    @Transactional
    @Operation(summary = "Replace a centre consultation prescription with a corrected one")
    public ResponseEntity<ClinicalDtos.DocumentResponse> supersede(
            @PathVariable String consultationPublicId,
            @PathVariable String prescriptionPublicId,
            @Valid @RequestBody ClinicalDtos.SupersedePrescriptionRequest request) {
        Prescription replacement = clinicalService.supersedePrescription(prescriptionPublicId,
                request.reason(), request.clinicalInformation(), prescriptionLines(request.items()));
        return ResponseEntity.ok(documentResponse(replacement));
    }

    @PostMapping("/{consultationPublicId}/investigations")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).INVESTIGATION_WRITE)")
    @Transactional
    @Operation(summary = "Request investigations from a centre consultation")
    public ResponseEntity<ClinicalDtos.DocumentResponse> issueInvestigation(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.IssueInvestigationRequest request) {
        Investigation investigation = clinicalService.issueInvestigation(consultationPublicId,
                request.clinicalInformation(),
                request.items().stream()
                        .map(i -> new ClinicalService.InvestigationLine(i.panelName(), i.panelCode(), i.notes()))
                        .toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ClinicalDtos.DocumentResponse(
                investigation.getPublicId(), investigation.getIssueNumber(),
                investigation.getStatus().name(), investigation.getIssueDate(),
                investigation.getExpiryDate(), investigation.getItems().size(),
                investigation.getSupersedesId()));
    }

    @PostMapping("/{consultationPublicId}/follow-up")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FOLLOW_UP_WRITE)")
    @Operation(summary = "Record a follow-up recommendation for a centre consultation")
    public ResponseEntity<Void> followUp(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.FollowUpRequest request) {
        clinicalService.recordFollowUp(consultationPublicId, request.recommendation(),
                request.reviewInterval(), request.preferredDate());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{consultationPublicId}/not-required")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(summary = "Record that a component is not needed for a centre consultation")
    public ResponseEntity<Void> notRequired(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.NotRequiredRequest request) {
        clinicalService.markNotRequired(consultationPublicId,
                ComponentType.valueOf(request.component()), request.reason());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @GetMapping("/{consultationPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_READ)")
    @Transactional(readOnly = true)
    @Operation(summary = "A centre consultation's session facts")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String consultationPublicId) {
        var c = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));
        var a = c.getCentreAppointment();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicId", c.getPublicId());
        row.put("scheduledStartAt", c.getScheduledStartAt());
        row.put("scheduledEndAt", c.getScheduledEndAt());
        row.put("startedAt", c.getStartedAt());
        row.put("endedAt", c.getEndedAt());
        row.put("modality", c.getModality() == null ? null : c.getModality().name());
        row.put("outcome", c.getOutcome() == null ? null : c.getOutcome().name());
        row.put("identityConfirmed", c.getIdentityConfirmed());
        row.put("terminationReason", c.getTerminationReason() == null ? null : c.getTerminationReason().name());
        row.put("safetyActionTaken", c.getSafetyActionTaken());
        row.put("centreName", a == null ? null : a.getCentre().getName());
        row.put("patientName", a == null ? null
                : a.getCentrePatient().getFirstName() + " " + a.getCentrePatient().getLastName());
        row.put("referralReason", a == null || a.getReferral() == null ? null : a.getReferral().getReferralReason());
        return ResponseEntity.ok(row);
    }

    @GetMapping("/{consultationPublicId}/record")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_READ)")
    @Transactional(readOnly = true)
    @Operation(summary = "What this centre consultation has produced so far")
    public ResponseEntity<Map<String, Object>> record(@PathVariable String consultationPublicId) {
        var consultation = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consultationPublicId", consultation.getPublicId());
        var appointment = consultation.getCentreAppointment();
        var bundle = appointment == null ? null
                : releaseBundleRepository.findByCentreAppointmentId(appointment.getId()).orElse(null);
        if (bundle == null) {
            body.put("bundle", null);
            body.put("components", List.of());
            body.put("prescriptions", List.of());
            body.put("investigations", List.of());
            body.put("followUps", List.of());
            return ResponseEntity.ok(body);
        }
        body.put("bundle", Map.of("publicId", bundle.getPublicId(), "status", bundle.getStatus().name()));
        body.put("components", releaseService.componentsOf(bundle.getId()).stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("componentType", c.getComponentType().name());
            row.put("complete", Boolean.TRUE.equals(c.getIsComplete()));
            row.put("notRequired", Boolean.TRUE.equals(c.getNotRequired()));
            row.put("notRequiredReason", c.getNotRequiredReason());
            row.put("settled", c.isSettled());
            return row;
        }).toList());
        body.put("prescriptions", prescriptionRepository.findAllByBundleId(bundle.getId()).stream()
                .map(p -> heading(p.getPublicId(), p.getIssueNumber(), p.getStatus().name(),
                        p.getItems().size(), p.getSupersedesId() != null))
                .toList());
        body.put("investigations", investigationRepository.findAllByBundleId(bundle.getId()).stream()
                .map(i -> heading(i.getPublicId(), i.getIssueNumber(), i.getStatus().name(),
                        i.getItems().size(), i.getSupersedesId() != null))
                .toList());
        body.put("followUps", followUpRepository.findAllByBundleId(bundle.getId()).stream().map(f -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("publicId", f.getPublicId());
            row.put("recommendation", f.getRecommendation());
            row.put("reviewInterval", f.getReviewInterval());
            return row;
        }).toList());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_READ)")
    @Transactional(readOnly = true)
    @Operation(summary = "Your centre consultations",
            description = "Approved, in-progress and recently finished centre consultations assigned to you. "
                    + "The doctor had no way to find a centre appointment except the assignment notification.")
    public ResponseEntity<List<Map<String, Object>>> mine() {
        Long doctorId = CurrentUser.require().getUserId();
        return ResponseEntity.ok(appointmentRepository.findDoctorWorklist(doctorId,
                        List.of(Status.APPROVED, Status.IN_PROGRESS, Status.COMPLETED, Status.NO_SHOW),
                        LocalDateTime.now().minusDays(14))
                .stream().map(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appointmentPublicId", a.getPublicId());
                    row.put("reference", a.getReference());
                    row.put("status", a.getStatus().name());
                    row.put("appointmentDate", a.getAppointmentDate());
                    row.put("scheduledEndAt", a.getScheduledEndAt());
                    row.put("room", a.getRoom());
                    row.put("centreName", a.getCentre().getName());
                    row.put("patientName", a.getCentrePatient().getFirstName() + " "
                            + a.getCentrePatient().getLastName());
                    row.put("centrePatientId", a.getCentrePatient().getCentrePatientId());
                    row.put("referralReason", a.getReferral() == null ? null : a.getReferral().getReferralReason());
                    row.put("consultationPublicId", consultationRepository.findByCentreAppointmentId(a.getId())
                            .map(c -> c.getPublicId()).orElse(null));
                    return row;
                }).toList());
    }

    private static List<ClinicalService.PrescriptionLine> prescriptionLines(
            List<ClinicalDtos.PrescriptionItemRequest> items) {
        return items.stream()
                .map(i -> new ClinicalService.PrescriptionLine(
                        i.medication(), i.strength(), i.frequency(), i.duration(), i.instructions()))
                .toList();
    }

    private static ClinicalDtos.DocumentResponse documentResponse(Prescription p) {
        return new ClinicalDtos.DocumentResponse(p.getPublicId(), p.getIssueNumber(),
                p.getStatus().name(), p.getIssueDate(), p.getExpiryDate(),
                p.getItems().size(), p.getSupersedesId());
    }

    private static Map<String, Object> heading(String publicId, String issueNumber, String status,
                                               int itemCount, boolean supersedes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicId", publicId);
        row.put("issueNumber", issueNumber);
        row.put("status", status);
        row.put("itemCount", itemCount);
        row.put("supersedesAnother", supersedes);
        return row;
    }

    private ClinicalDtos.NoteResponse toResponse(CentreConsultationNote n) {
        return new ClinicalDtos.NoteResponse(
                n.getPublicId(), n.getVersion(), n.getClinicalNote(),
                n.getSignedAt(), n.getSignedBy(), n.getSupersededAt(),
                n.getAmendmentReason(), n.getFollowUpRecommendation(), n.getFollowUpTimeline());
    }
}
