package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.clinical.*;
import com.fnph.telepsychiatric.consultation.ConsultationNote;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/clinical/consultations/{consultationPublicId}")
@RequiredArgsConstructor
@Tag(name = "Clinical Authoring")
public class ClinicalController {

    private final ClinicalService clinicalService;

    @PutMapping("/note")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_WRITE)")
    @Operation(
            summary = "Save the consultation note",
            description = """
                    Creates or updates the draft. Call it as often as you like while the
                    session runs.

                    Refused once the note is signed. A signed note is amended, not edited.

                    **Only the consulting clinician can write here.** Another doctor on the
                    same patient gets 400, because clinician-authored content is not altered
                    by anyone else.

                    **Requires** `clinical_note.write`, held by the Doctor alone.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Draft saved.",
                    content = @Content(schema = @Schema(implementation = ClinicalDtos.NoteResponse.class))),
            @ApiResponse(responseCode = "400", description = "Already signed, or another "
                    + "clinician's consultation.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ClinicalDtos.NoteResponse> saveNote(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.NoteRequest request) {
        return ResponseEntity.ok(toResponse(
                clinicalService.saveDraft(consultationPublicId, request.clinicalNote())));
    }

    @PostMapping("/note/sign")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(
            summary = "Sign the note",
            description = """
                    **Irreversible.** After this the note cannot be edited, only superseded
                    by an amendment that names what it replaced and why.

                    Signing opens the release bundle and marks the note component complete,
                    which starts the completeness check the Hub Coordinator later acts on.

                    An empty note cannot be signed.

                    **Requires** `clinical_note.sign`, held by the Doctor alone.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed."),
            @ApiResponse(responseCode = "400", description = "Nothing to sign, or empty.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ClinicalDtos.NoteResponse> sign(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.SignNoteRequest request) {
        return ResponseEntity.ok(toResponse(clinicalService.sign(consultationPublicId,
                request.followUpRecommendation(), request.followUpTimeline())));
    }

    @PostMapping("/note/amend")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(
            summary = "Amend a signed note",
            description = """
                    Creates a new version and marks the previous one superseded. Both stay
                    in the record.

                    This is the only way to change a signed note, and it is deliberate.
                    Editing in place destroys the one thing an investigation would want,
                    which is what the clinician wrote at the time.

                    **Requires** `clinical_note.sign`.
                    """)
    @ApiResponse(responseCode = "200", description = "New version created.")
    public ResponseEntity<ClinicalDtos.NoteResponse> amend(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.AmendNoteRequest request) {
        return ResponseEntity.ok(toResponse(clinicalService.amend(
                consultationPublicId, request.clinicalNote(), request.amendmentReason())));
    }

    @GetMapping("/note/history")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_READ)")
    @Operation(
            summary = "Every version of the note",
            description = """
                    Oldest first, with what each amendment said and why.

                    **Requires** `clinical_note.read`. Pharmacy and laboratory do not hold
                    it on either pathway.
                    """)
    @ApiResponse(responseCode = "200", description = "Versions returned.")
    public ResponseEntity<List<ClinicalDtos.NoteResponse>> noteHistory(
            @PathVariable String consultationPublicId) {
        return ResponseEntity.ok(clinicalService.noteHistory(consultationPublicId)
                .stream().map(this::toResponse).toList());
    }

    @PostMapping("/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_WRITE)")
    @Operation(
            summary = "Issue a prescription",
            description = """
                    Sends it to the pharmacist the Hub Coordinator assigned at approval, for
                    transcription and professional verification.

                    **It does not come back to you.** A concern raised in review reaches the
                    Hub Coordinator and the multidisciplinary team. Correcting it means
                    issuing a new prescription that supersedes this one, which is the only
                    version where the prescriber decided what the patient takes.

                    `clinicalInformation` is what pharmacy sees. **The clinical note is not**,
                    on either pathway.

                    A prescription with no items is refused. If none is needed, record that
                    through `/not-required` instead.

                    **Requires** `prescription.write`, held by the Doctor alone.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Issued and sent for review.",
                    content = @Content(schema = @Schema(implementation = ClinicalDtos.DocumentResponse.class))),
            @ApiResponse(responseCode = "400", description = "No items.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ClinicalDtos.DocumentResponse> issuePrescription(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.IssuePrescriptionRequest request) {

        Prescription prescription = clinicalService.issuePrescription(
                consultationPublicId, request.clinicalInformation(),
                request.items().stream()
                        .map(i -> new ClinicalService.PrescriptionLine(
                                i.medication(), i.strength(), i.frequency(),
                                i.duration(), i.instructions()))
                        .toList());

        return ResponseEntity.status(HttpStatus.CREATED).body(new ClinicalDtos.DocumentResponse(
                prescription.getPublicId(), prescription.getIssueNumber(),
                prescription.getStatus().name(), prescription.getIssueDate(),
                prescription.getExpiryDate(), prescription.getItems().size(),
                prescription.getSupersedesId()));
    }

    @PostMapping("/prescriptions/{prescriptionPublicId}/supersede")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_WRITE)")
    @Operation(
            summary = "Replace a prescription with a corrected one",
            description = """
                    The only route to changing a prescription.

                    The previous one is marked superseded, and **any copy the patient
                    already holds stops verifying as valid**, so it cannot be dispensed at a
                    pharmacy counter. The new one goes for review like any other.

                    Use this after a concern raised at the multidisciplinary team. There is
                    no edit, because a prescription changeable by anyone other than the
                    prescriber is not a prescription.

                    **Requires** `prescription.write`.
                    """)
    @ApiResponse(responseCode = "200", description = "Replacement issued, previous withdrawn.")
    public ResponseEntity<ClinicalDtos.DocumentResponse> supersede(
            @PathVariable String consultationPublicId,
            @PathVariable String prescriptionPublicId,
            @Valid @RequestBody ClinicalDtos.SupersedePrescriptionRequest request) {

        Prescription replacement = clinicalService.supersedePrescription(
                prescriptionPublicId, request.reason(), request.clinicalInformation(),
                request.items().stream()
                        .map(i -> new ClinicalService.PrescriptionLine(
                                i.medication(), i.strength(), i.frequency(),
                                i.duration(), i.instructions()))
                        .toList());

        return ResponseEntity.ok(new ClinicalDtos.DocumentResponse(
                replacement.getPublicId(), replacement.getIssueNumber(),
                replacement.getStatus().name(), replacement.getIssueDate(),
                replacement.getExpiryDate(), replacement.getItems().size(),
                replacement.getSupersedesId()));
    }

    @PostMapping("/investigations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).INVESTIGATION_WRITE)")
    @Operation(
            summary = "Issue an investigation request",
            description = """
                    Sends it to the laboratory technician assigned at approval. Same one-way
                    rule as a prescription.

                    A request with no panels is refused. If none is needed, record that
                    through `/not-required`.

                    **Requires** `investigation.write`, held by the Doctor alone.
                    """)
    @ApiResponse(responseCode = "201", description = "Issued and sent for review.")
    public ResponseEntity<ClinicalDtos.DocumentResponse> issueInvestigation(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.IssueInvestigationRequest request) {

        Investigation investigation = clinicalService.issueInvestigation(
                consultationPublicId, request.clinicalInformation(),
                request.items().stream()
                        .map(i -> new ClinicalService.InvestigationLine(
                                i.panelName(), i.panelCode(), i.notes()))
                        .toList());

        return ResponseEntity.status(HttpStatus.CREATED).body(new ClinicalDtos.DocumentResponse(
                investigation.getPublicId(), investigation.getIssueNumber(),
                investigation.getStatus().name(), investigation.getIssueDate(),
                investigation.getExpiryDate(), investigation.getItems().size(),
                investigation.getSupersedesId()));
    }

    @PostMapping("/follow-up")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FOLLOW_UP_WRITE)")
    @Operation(
            summary = "Record the follow-up recommendation",
            description = """
                    What should happen next and when. Released to the patient with the rest
                    of the bundle.

                    **Requires** `follow_up.write`.
                    """)
    @ApiResponse(responseCode = "201", description = "Recorded.")
    public ResponseEntity<Void> followUp(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.FollowUpRequest request) {
        clinicalService.recordFollowUp(consultationPublicId, request.recommendation(),
                request.reviewInterval(), request.preferredDate());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/not-required")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CLINICAL_NOTE_SIGN)")
    @Operation(
            summary = "Record that a component is not needed",
            description = """
                    Most consultations produce no investigation request. Saying so is what
                    lets the bundle become ready.

                    **Leaving it out is not the same thing.** Without this the release check
                    cannot tell "the doctor decided none was needed" from "the doctor has
                    not got to it yet", so the bundle waits forever and the coordinator
                    eventually releases it by guessing.

                    The clinical note cannot be marked not required. A consultation always
                    produces one.

                    **Requires** `clinical_note.sign`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Recorded."),
            @ApiResponse(responseCode = "400",
                    description = "No reason, or an attempt to skip the clinical note.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> notRequired(
            @PathVariable String consultationPublicId,
            @Valid @RequestBody ClinicalDtos.NotRequiredRequest request) {
        clinicalService.markNotRequired(consultationPublicId,
                ComponentType.valueOf(request.component()), request.reason());
        return ResponseEntity.noContent().build();
    }

    private ClinicalDtos.NoteResponse toResponse(ConsultationNote n) {
        return new ClinicalDtos.NoteResponse(
                n.getPublicId(), n.getVersion(), n.getClinicalNote(),
                n.getSignedAt(), n.getSignedBy(), n.getSupersededAt(),
                n.getAmendmentReason(), n.getFollowUpRecommendation(), n.getFollowUpTimeline());
    }
}
