package com.fnph.telepsychiatric.centre.api;

import com.fnph.telepsychiatric.centre.*;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
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
    public ResponseEntity<CentreReferralResponse> create(
            @Valid @RequestBody CreateReferralRequest request) {

        var centre = centreRepository.findById(CurrentUser.require().getCentreId())
                .orElseThrow(() -> new EntityNotFoundException("No centre on this account"));
        var patient = patientRepository.findByPublicId(request.centrePatientPublicId())
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
    public ResponseEntity<List<CentreReferralResponse>> history(
            @PathVariable String centrePatientPublicId) {
        var patient = patientRepository.findByPublicId(centrePatientPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient at this centre"));
        return ResponseEntity.ok(referralService.historyFor(patient.getId())
                .stream().map(this::toResponse).toList());
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
    public ResponseEntity<List<Map<String, Object>>> incoming() {
        return ResponseEntity.ok(referralService.incoming().stream()
                .map(this::toQueueItem).toList());
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
            @RequestParam String notes) {
        referralService.markTreated(receiptPublicId, notes);
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
    public ResponseEntity<CentreReferralService.UtilisationSummary> utilisation() {
        return ResponseEntity.ok(referralService.utilisation());
    }

    private CentreReferralResponse toResponse(CentreReferral r) {
        return new CentreReferralResponse(
                r.getPublicId(), r.getReference(),
                r.getCentrePatient().getCentrePatientId(),
                r.getCentrePatient().getFirstName() + " " + r.getCentrePatient().getLastName(),
                r.getStatus().name(), r.getUrgency().name(),
                r.getConsentAcceptedAt(), r.getConsentAcceptedBy(),
                r.getSubmittedAt(), r.getCreatedAt());
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
        return row;
    }
}
