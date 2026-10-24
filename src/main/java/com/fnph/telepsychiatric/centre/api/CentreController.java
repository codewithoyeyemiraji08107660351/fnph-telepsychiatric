package com.fnph.telepsychiatric.centre.api;

import com.fnph.telepsychiatric.centre.*;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/centres/me")
@RequiredArgsConstructor
@Tag(name = "Centre of Excellence")
public class CentreController {

    private final CentreReferralService referralService;
    private final CentreReferralRepository referralRepository;
    private final CentrePatientRepository patientRepository;
    private final CenterRepository centreRepository;
    private final com.fnph.telepsychiatric.appointment.CentreAppointmentRepository appointmentRepository;
    private final com.fnph.telepsychiatric.configuration.ConfigurationService configuration;
    private final com.fnph.telepsychiatric.clinical.api.BundleContentAssembler contentAssembler;
    private final CentreBookingService bookingService;
    private final CentreVitalsService vitalsService;
    private final com.fnph.telepsychiatric.document.IssuedDocumentRepository documentRepository;

    @PostMapping("/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_CREATE)")
    @Operation(
            summary = "Create a referral",
            description = """
                    Creates a referral in `DRAFT`. It is not visible to FNPH until it is
                    submitted with the patient's consent.

                    **One referral per visit.** A patient seen three times has three
                    referrals, each with its own presenting condition. Reusing an earlier
                    one would overwrite the clinical history that made the consultation
                    necessary.

                    **The referral reason is the doctor's only context.** A centre patient
                    is never an FNPH patient: the offline FNPH record is not linked or
                    retrieved even when the patient also holds an FNPH number, so anything
                    the consulting doctor knows comes from what is written here.

                    The centre must be `ACTIVE`. A centre still in setup has no coordinator
                    to receive a released bundle, so a referral would strand clinical
                    output.

                    **Requires** `centre_referral.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Referral created in draft.",
                    content = @Content(schema = @Schema(implementation = CentreReferralResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "The centre is not active, or the referral reason is missing.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<CentreReferralResponse> create(
            @Valid @RequestBody CreateReferralRequest request) {

        var centre = centreRepository.findById(CurrentUser.require().getCentreId())
                .orElseThrow(() -> new EntityNotFoundException("No centre on this account"));
        var patient = patientRepository
                .findByCentreIdAndPublicId(centre.getId(), request.centrePatientPublicId())
                .orElseThrow(() -> new EntityNotFoundException("No such patient at this centre"));

        var referral = referralService.create(centre, patient,
                new CentreReferralService.ReferralDetails(
                        request.referralReason(), request.assessment(),
                        request.currentCondition(), request.relevantMedicines(),
                        request.previousResults(),
                        request.urgency() == null ? ReferralUrgency.ROUTINE
                                : ReferralUrgency.valueOf(request.urgency())));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(referral));
    }

    @PutMapping("/referrals/{referralPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_CREATE)")
    @Operation(
            summary = "Correct a draft or returned referral",
            description = """
                    Replaces the clinical content of a referral that is still a draft, or that
                    FNPH returned asking for more. Send it again with consent afterwards.

                    Refused once submitted: what FNPH approved is what the doctor reads.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated.",
                    content = @Content(schema = @Schema(implementation = CentreReferralResponse.class))),
            @ApiResponse(responseCode = "409", description = "Already submitted.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<CentreReferralResponse> update(
            @PathVariable String referralPublicId,
            @Valid @RequestBody UpdateReferralRequest request) {
        var referral = referralService.update(referralPublicId,
                new CentreReferralService.ReferralDetails(
                        request.referralReason(), request.assessment(),
                        request.currentCondition(), request.relevantMedicines(),
                        request.previousResults(),
                        request.urgency() == null ? null : ReferralUrgency.valueOf(request.urgency())));
        return ResponseEntity.ok(toResponse(referral));
    }

    @PostMapping("/referrals/{referralPublicId}/submit")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_CREATE)")
    @Operation(
            summary = "Record consent and submit the referral",
            description = """
                    Captures the patient's consent and makes the referral visible to FNPH.

                    **Consent belongs to this referral, not to the patient.** A patient
                    consented to a consultation in March; that is not consent to one in
                    September, and a system that carried it over would have stopped asking.

                    Record who at the centre witnessed it. The patient is in the room with
                    that person, not with anyone at FNPH.

                    **Requires** `centre_referral.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Submitted.",
                    content = @Content(schema = @Schema(implementation = CentreReferralResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Already submitted, or consent details are missing.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<CentreReferralResponse> submit(
            @PathVariable String referralPublicId,
            @Parameter(description = "The consent document version the patient accepted.",
                    example = "FNPH-COE-CONSENT-01", required = true)
            @RequestParam String consentVersion,
            @Parameter(description = "Who at the centre witnessed it.",
                    example = "Coordinator Musa Danjuma", required = true)
            @RequestParam String consentWitnessedBy) {
        return ResponseEntity.ok(toResponse(referralService.submit(
                referralPublicId, consentVersion, consentWitnessedBy)));
    }

    @GetMapping("/appointments")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_READ)")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Operation(
            summary = "This centre's consultations",
            description = "Requested, approved and recent consultations at this centre, soonest first. "
                    + "The centre had no way to find an appointment to join; the booking request "
                    + "returned its id once and nothing listed it again.")
    public ResponseEntity<List<Map<String, Object>>> appointments() {
        Long centreId = TenantContext.requireCentreId();
        int lead = configuration.getInt(com.fnph.telepsychiatric.configuration.ConfigurationKeys.ROOM_OPEN_LEAD_MINUTES);
        return ResponseEntity.ok(appointmentRepository
                .findAllByCentreIdAndAppointmentDateAfterOrderByAppointmentDateAsc(
                        centreId, java.time.LocalDateTime.now().minusDays(7))
                .stream().map(a -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", a.getPublicId());
                    row.put("reference", a.getReference());
                    row.put("status", a.getStatus().name());
                    row.put("appointmentDate", a.getAppointmentDate());
                    row.put("scheduledEndAt", a.getScheduledEndAt());
                    row.put("joinWindowOpensAt", a.getAppointmentDate().minusMinutes(lead));
                    row.put("room", a.getRoom());
                    row.put("patientName", a.getCentrePatient().getFirstName() + " "
                            + a.getCentrePatient().getLastName());
                    row.put("centrePatientId", a.getCentrePatient().getCentrePatientId());
                    row.put("referralReference", a.getReferral() == null ? null : a.getReferral().getReference());
                    // Why FNPH returned the request. It was only in the notification.
                    row.put("returnedReason", a.getReturnedReason());
                    row.put("cancellationReason", a.getCancellationReason());
                    row.put("cancelledAt", a.getCancelledAt());
                    row.put("noShowReason", a.getNoShowReason());
                    return row;
                }).toList());
    }

    @PostMapping("/appointments/{appointmentPublicId}/cancel")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_CANCEL)")
    @Operation(
            summary = "Cancel one of this centre's consultations",
            description = """
                    Cancels a pending request or a confirmed consultation before it starts,
                    for example when the patient cannot attend.

                    The time is freed and FNPH is told. The referral reopens so you can
                    request another time without entering it again.

                    Refused once the consultation has started.

                    **Requires** `appointment.cancel`. Scoped to your centre.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled."),
            @ApiResponse(responseCode = "409", description = "Already started, or not pending or confirmed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> cancelAppointment(
            @PathVariable String appointmentPublicId,
            @Parameter(description = "Why. Goes to FNPH.", required = true)
            @RequestParam String reason) {
        var a = bookingService.cancelByCentre(appointmentPublicId, reason);
        // The centre does not see wallet figures, so no refund flag here.
        return ResponseEntity.ok(Map.of(
                "reference", a.getReference(),
                "status", a.getStatus().name()));
    }

    @PostMapping("/appointments/{appointmentPublicId}/vitals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).VITALS_SUBMIT)")
    @Operation(
            summary = "Record the patient's vitals for a consultation",
            description = """
                    Readings taken at the centre before the session, so the FNPH doctor
                    sees numbers rather than a photograph of them. At least one reading.
                    Same plausibility ranges as FNPH nursing; BMI is computed from height
                    and weight, never sent.

                    **Requires** `vitals.submit`. Scoped to your centre.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded."),
            @ApiResponse(responseCode = "409", description = "Out of range, no readings, or the "
                    + "consultation is closed.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> recordVitals(
            @PathVariable String appointmentPublicId,
            @RequestBody CentreVitalsRequest body) {
        var saved = vitalsService.record(appointmentPublicId, new com.fnph.telepsychiatric.clinical.VitalsService.VitalsEntry(
                body.systolic(), body.diastolic(), body.heartRate(), body.respiratoryRate(),
                body.temperature(), body.weightKg(), body.heightCm(), body.bloodOxygen(),
                body.bloodGlucose(), null, "CENTRE_STAFF", body.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CentreClinicalViews.vitals(saved));
    }

    @GetMapping("/appointments/{appointmentPublicId}/vitals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_READ)")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Operation(summary = "Vitals recorded for one of this centre's consultations, newest first")
    public ResponseEntity<List<Map<String, Object>>> vitals(@PathVariable String appointmentPublicId) {
        return ResponseEntity.ok(vitalsService.forOwnAppointment(appointmentPublicId).stream()
                .map(CentreClinicalViews::vitals).toList());
    }

    @Schema(name = "CentreVitalsRequest", description = "Any subset; at least one reading.")
    public record CentreVitalsRequest(Integer systolic, Integer diastolic, Integer heartRate,
                                      Integer respiratoryRate, Double temperature, Double weightKg,
                                      Double heightCm, Integer bloodOxygen, Double bloodGlucose,
                                      @jakarta.validation.constraints.Size(max = 1000) String notes) {
    }

    @GetMapping("/referrals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_READ)")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Operation(
            summary = "This centre's referrals",
            description = "Every referral at this centre, newest first, optionally by status. "
                    + "Before this a coordinator had to open each patient to see what was in flight.")
    public ResponseEntity<List<CentreReferralResponse>> referrals(
            @RequestParam(required = false) ReferralStatus status) {
        Long centreId = TenantContext.requireCentreId();
        var rows = status == null
                ? referralRepository.findAllByCentreIdOrderByCreatedAtDesc(centreId)
                : referralRepository.findAllByCentreIdAndStatusOrderByCreatedAtDesc(centreId, status);
        return ResponseEntity.ok(rows.stream().map(this::toResponse).toList());
    }

    @GetMapping("/patients/{centrePatientPublicId}/referrals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_READ)")
    @Operation(
            summary = "A patient's referral history",
            description = """
                    Every referral for this patient, newest first.

                    This is why referrals are per visit. The history shows how the patient
                    presented on each occasion, which is what a coordinator needs when
                    referring them again and what the consulting doctor reads.

                    Scoped to your centre. A patient at another centre is not visible by
                    any route.

                    **Requires** `centre_referral.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "History returned.")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<CentreReferralResponse>> history(
            @PathVariable String centrePatientPublicId) {
        var patient = patientRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), centrePatientPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient at this centre"));
        return ResponseEntity.ok(referralService.historyFor(patient.getId())
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/treated")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_BUNDLE_READ)")
    @Operation(
            summary = "Bundles this centre has treated",
            description = """
                    Care bundles this centre has already acted on, most recent first, with
                    the notes recorded at the time.

                    **The service method existed and nothing called it.** A centre could
                    mark a bundle treated and then had no way to see what it had treated,
                    so the notes required at that moment went somewhere nobody could read.
                    This is the centre's record of care given, and it is the answer when
                    FNPH asks what was done.

                    Centre-scoped: `treatedHistory()` resolves the calling centre and
                    cannot reach another's receipts.

                    **Requires** `centre_bundle.read`.
                    """)
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> treated(
            @Parameter(description = "Whose history. COORDINATION (default), PHARMACY, LABORATORY or HIM.")
            @RequestParam(defaultValue = "COORDINATION") CentreCapability capability) {
        return ResponseEntity.ok(referralService.treatedHistory(capability).stream()
                .map(r -> toQueueItem(r, capability)).toList());
    }

    @GetMapping("/incoming")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_BUNDLE_READ)")
    @Operation(
            summary = "Care bundles waiting to be acted on",
            description = """
                    Completed care bundles delivered from FNPH, oldest first.

                    Each arrives complete: the signed consultation note, any
                    pharmacy-reviewed prescription, any laboratory-reviewed request and the
                    follow-up recommendation. FNPH releases all of it or none, so a patient
                    is never given part of their care plan.

                    Tied to the centre-local patient identifier and name so staff can match
                    it to the person in front of them.

                    **Requires** `centre_bundle.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned, oldest first.")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> incoming(
            @Parameter(description = """
                    Whose queue. COORDINATION (default) is every bundle not yet closed by the
                    coordinator. PHARMACY lists bundles with a prescription the pharmacy has not
                    acted on, LABORATORY those with an investigation request, HIM every bundle
                    records has not filed. One service acting no longer clears the others.
                    """)
            @RequestParam(defaultValue = "COORDINATION") CentreCapability capability) {
        return ResponseEntity.ok(referralService.incoming(capability).stream()
                .map(r -> toQueueItem(r, capability)).toList());
    }

    @GetMapping("/incoming/{receiptPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_BUNDLE_READ)")
    @Operation(
            summary = "What a delivered care bundle contains",
            description = """
                    The released care plan and its documents, for the person who has to act
                    on it.

                    The incoming list only named the patient and the date, and nothing else
                    returned the contents, so a centre pharmacist was asked to mark a bundle
                    treated with no way to see what had been prescribed.

                    Shaped by role, the same way FNPH separates them:
                    - the signed consultation note: holders of `consultation.read` (the
                      Centre Hub Coordinator)
                    - prescriptions: `prescription.read` or `consultation.read`
                    - investigation requests: `investigation.read` or `consultation.read`
                    - the follow-up recommendation: everyone who can read the bundle

                    `documents` are the issued PDFs, downloaded through
                    `GET /api/v1/documents/{publicId}/file` against the same allowance as
                    any other copy.

                    The first open is recorded with who opened it, so FNPH can see a bundle
                    that arrived and was never looked at. A supervised view does not count
                    as opening it.
                    """)
    @ApiResponse(responseCode = "200", description = "Contents returned.")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<CentreBundleDetail> incomingItem(@PathVariable String receiptPublicId) {
        CentreBundleReceipt receipt = com.fnph.telepsychiatric.audit.SupervisionContext.isActive()
                ? referralService.receipt(receiptPublicId)
                : referralService.open(receiptPublicId);
        var bundle = receipt.getBundle();
        var appointment = receipt.getCentreAppointment();
        var content = contentAssembler.contentOf(bundle);

        boolean clinician = has(com.fnph.telepsychiatric.authz.Permissions.CONSULTATION_READ);
        boolean prescriptions = clinician || has(com.fnph.telepsychiatric.authz.Permissions.PRESCRIPTION_READ);
        boolean investigations = clinician || has(com.fnph.telepsychiatric.authz.Permissions.INVESTIGATION_READ);

        var documents = documentRepository
                .findAllByBundleIdAndCentreId(bundle.getId(), TenantContext.requireCentreId())
                .stream()
                .filter(d -> switch (d.getDocumentType()) {
                    case PRESCRIPTION -> prescriptions;
                    case INVESTIGATION_REQUEST -> investigations;
                    default -> true;
                })
                .map(d -> new CentreBundleDetail.Document(
                        d.getPublicId(),
                        d.getDocumentType().name(),
                        d.getIssueNumber(),
                        d.effectiveStatus(java.time.LocalDateTime.now()).name(),
                        d.getExpiresAt(),
                        d.getDownloadCount(),
                        d.getMaxDownloads()))
                .toList();

        return ResponseEntity.ok(new CentreBundleDetail(
                receipt.getPublicId(),
                receipt.getCentrePatient().getCentrePatientId(),
                receipt.getCentrePatient().getFirstName() + " " + receipt.getCentrePatient().getLastName(),
                appointment.getReference(),
                appointment.getAppointmentDate(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getFullName(),
                bundle.getReleasedAt(),
                receipt.getDeliveredAt(),
                receipt.getFirstOpenedAt(),
                receipt.getFirstOpenedBy(),
                receipt.getTreatedAt(),
                receipt.getTreatedBy(),
                receipt.getTreatmentNotes(),
                clinician ? content.clinicalNote() : null,
                prescriptions ? content.prescriptions() : List.of(),
                investigations ? content.investigations() : List.of(),
                content.followUps(),
                documents,
                referralService.actionsOn(receipt).stream()
                        .map(a -> new CentreBundleDetail.Action(a.getCapability().name(),
                                a.getActedAt(), a.getActedBy(), a.getNotes()))
                        .toList()));
    }

    /**
     * A delivered bundle as the centre sees it.
     *
     * Lists the centre may not see are empty rather than absent, so the screen
     * has one shape to render.
     */
    @Schema(name = "CentreBundleDetail")
    public record CentreBundleDetail(
            String publicId,
            String centrePatientId,
            String patientName,
            String appointmentReference,
            java.time.LocalDateTime appointmentDate,
            String doctorName,
            java.time.LocalDateTime releasedAt,
            java.time.LocalDateTime deliveredAt,
            java.time.LocalDateTime firstOpenedAt,
            String firstOpenedBy,
            java.time.LocalDateTime treatedAt,
            String treatedBy,
            String treatmentNotes,
            @Schema(nullable = true)
            com.fnph.telepsychiatric.clinical.api.ReleaseBundleResponse.ClinicalNoteContent clinicalNote,
            List<com.fnph.telepsychiatric.clinical.api.ReleaseBundleResponse.PrescriptionContent> prescriptions,
            List<com.fnph.telepsychiatric.clinical.api.ReleaseBundleResponse.InvestigationContent> investigations,
            List<com.fnph.telepsychiatric.clinical.api.ReleaseBundleResponse.FollowUpContent> followUps,
            List<Document> documents,
            List<Action> actions) {

        /** What each service at the centre recorded doing with this bundle. */
        public record Action(String capability, java.time.LocalDateTime actedAt, String actedBy,
                             String notes) {
        }

        public record Document(String publicId, String documentType, String issueNumber,
                               String status, java.time.LocalDateTime expiresAt,
                               Integer downloadCount, Integer maxDownloads) {
        }
    }

    private static boolean has(String permission) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
    }

    @PostMapping("/incoming/{receiptPublicId}/treated")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_BUNDLE_MARK_TREATED)")
    @Operation(
            summary = "Mark a bundle treated",
            description = """
                    Records what the centre did and moves the item to history.

                    **Notes are required.** "Treated" with no record leaves the centre
                    unable to say, months later, whether the prescription was actually
                    dispensed or the investigation actually taken.

                    **Requires** `centre_bundle.mark_treated`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Marked treated and moved to history."),
            @ApiResponse(responseCode = "400", description = "No notes given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> markTreated(
            @PathVariable String receiptPublicId,
            @Parameter(description = "What was done.",
                    example = "Prescription dispensed on 14 October. Patient advised on dosing.",
                    required = true)
            @RequestParam String notes,
            @Parameter(description = "Which service is recording. Must match your role. "
                    + "COORDINATION (default) also closes the bundle for the centre.")
            @RequestParam(defaultValue = "COORDINATION") CentreCapability capability) {
        referralService.markTreated(receiptPublicId, notes, capability);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/utilisation")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REPORT_READ)")
    @Operation(
            summary = "This centre's activity",
            description = """
                    Consultation and utilisation counts for your centre.

                    **Counts only. No wallet balance and no amounts.** Centres do not make
                    patient payments and do not see wallet figures; Finance credits the
                    wallet from programme funding and holds that view. If a booking is
                    refused for funding, FNPH will tell you.

                    `bundlesAwaitingAction` is the number that matters day to day: care
                    bundles delivered and not yet acted on.

                    **Requires** `centre_report.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Counts returned.")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<CentreReferralService.UtilisationSummary> utilisation() {
        return ResponseEntity.ok(referralService.utilisation());
    }

    private CentreReferralResponse toResponse(CentreReferral r) {
        // The reason FNPH gave lives on the returned appointment request; the
        // centre only saw it in a notification.
        String returnedReason = r.getStatus() != ReferralStatus.RETURNED ? null
                : appointmentRepository.findFirstByCentreIdAndReferralIdAndStatusOrderByIdDesc(
                                r.getCentre().getId(), r.getId(), com.fnph.telepsychiatric.appointment.Status.REJECTED)
                        .map(com.fnph.telepsychiatric.appointment.CentreAppointment::getReturnedReason)
                        .orElse(null);
        return new CentreReferralResponse(
                r.getPublicId(), r.getReference(),
                r.getCentrePatient().getCentrePatientId(),
                r.getCentrePatient().getFirstName() + " " + r.getCentrePatient().getLastName(),
                r.getStatus().name(), r.getUrgency().name(),
                r.getConsentAcceptedAt(), r.getConsentAcceptedBy(),
                r.getSubmittedAt(), r.getCreatedAt(),
                r.getReferralReason(), r.getAssessment(), r.getCurrentCondition(),
                r.getRelevantMedicines(), r.getPreviousResults(), returnedReason);
    }

    private Map<String, Object> toQueueItem(CentreBundleReceipt receipt, CentreCapability capability) {
        Map<String, Object> row = toQueueItem(receipt);
        if (capability != CentreCapability.COORDINATION) {
            // A service's own record, not the coordinator's.
            var own = referralService.actionsOn(receipt).stream()
                    .filter(a -> a.getCapability() == capability).findFirst().orElse(null);
            row.put("treatedAt", own == null ? null : own.getActedAt());
            row.put("treatedBy", own == null ? null : own.getActedBy());
            row.put("treatmentNotes", own == null ? null : own.getNotes());
            row.put("outstanding", own == null);
        }
        return row;
    }

    private Map<String, Object> toQueueItem(CentreBundleReceipt receipt) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", receipt.getPublicId());
        row.put("centrePatientId", receipt.getCentrePatient().getCentrePatientId());
        row.put("patientName", receipt.getCentrePatient().getFirstName() + " "
                + receipt.getCentrePatient().getLastName());
        row.put("appointmentReference", receipt.getCentreAppointment().getReference());
        row.put("deliveredAt", receipt.getDeliveredAt());
        row.put("firstOpenedAt", receipt.getFirstOpenedAt());
        row.put("outstanding", receipt.isOutstanding());
        // The treated history promised "the notes recorded at the time" and
        // returned none of them.
        row.put("treatedAt", receipt.getTreatedAt());
        row.put("treatedBy", receipt.getTreatedBy());
        row.put("treatmentNotes", receipt.getTreatmentNotes());
        return row;
    }
}
