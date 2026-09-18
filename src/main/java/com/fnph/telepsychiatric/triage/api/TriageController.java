package com.fnph.telepsychiatric.triage.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.triage.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Triage and Consent")
public class TriageController {

    private final TriageService triageService;
    private final PatientRepository patientRepository;

    @GetMapping("/triage/questions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TRIAGE_SUBMIT)")
    @Operation(
            summary = "The triage questions to ask",
            description = """
                    The published question set, in order.

                    **Ask these before payment, not after.** The point of triage is to find
                    the patients this service cannot help before they pay for a consultation
                    that will not help them.

                    Do not show the stop answers to the patient. They are returned so the
                    client can render the form, and a patient who can see which answer ends
                    the journey will give the other one.

                    **Returns 400 if no set is published.** The seeded questions are
                    placeholders and are deliberately left as a draft: the wording decides
                    which patients are turned away from a psychiatric service, so it has to
                    be FNPH's own.

                    **Requires** `triage.submit`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Questions returned in order."),
            @ApiResponse(responseCode = "400",
                    description = "No published set. FNPH must supply and publish the wording.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> questions() {
        TriageQuestionSet set = triageService.activeQuestions("FNPH_PATIENT");

        return ResponseEntity.ok(Map.of(
                "version", set.getVersion(),
                "questions", set.getQuestions().stream().map(q -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", q.getPublicId());
                    row.put("sequence", q.getSequence());
                    row.put("questionText", q.getQuestionText());
                    return row;
                }).toList()));
    }

    @PostMapping("/triage/responses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TRIAGE_SUBMIT)")
    @Operation(
            summary = "Submit triage answers",
            description = """
                    Evaluates the answers and either clears the patient to continue or stops
                    the journey.

                    **A `STOPPED` outcome is a redirection, not a rejection.** The response
                    carries the escalation text the patient should be shown, including the
                    emergency number, and says plainly that nothing has been charged and no
                    appointment made. Show it in full.

                    Every question must be answered. A partial submission is refused rather
                    than treated as a pass, because the unanswered question is the one most
                    likely to be the risky one.

                    **The most recent submission is what counts at booking.** A patient whose
                    situation genuinely changed can submit again and proceed, and the earlier
                    answers stay in the record because that pattern is clinically
                    interesting.

                    Answers are keyed by question `publicId`, with the value `YES` or `NO`.

                    **Requires** `triage.submit`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Evaluated. Read `outcome`; a 201 does not mean cleared."),
            @ApiResponse(responseCode = "400",
                    description = "A question was not answered, or no set is published.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody Map<String, String> answers, HttpServletRequest http) {

        var patient = patientRepository.findById(CurrentUser.require().getPatientId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No patient record on this account"));

        TriageResponse response = triageService.submit(
                patient, "FNPH_PATIENT", answers, clientIp(http));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("publicId", response.getPublicId());
        body.put("outcome", response.getOutcome());
        body.put("mayProceed", "PROCEED".equals(response.getOutcome()));
        body.put("stopReason", response.getStopReason());
        body.put("escalation", response.getEscalationShown());

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/triage/mine")
    // The caller's own history. triage.read is the staff permission and the
    // patient does not hold it, so this answered 403 to the only person it serves.
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).TRIAGE_SUBMIT)")
    @Operation(
            summary = "My triage history",
            description = """
                    Every submission, newest first.

                    Append-only. A patient who answered yes to a risk question and then
                    submitted again with no leaves both in the record, because that pattern
                    matters clinically and deleting the first answer would hide it.

                    **Requires** `triage.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "History returned.")
    public ResponseEntity<List<Map<String, Object>>> mine() {
        return ResponseEntity.ok(triageService
                .historyFor(CurrentUser.require().getPatientId()).stream().map(r -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", r.getPublicId());
                    row.put("version", r.getTriageVersion());
                    row.put("outcome", r.getOutcome());
                    row.put("stopReason", r.getStopReason());
                    row.put("submittedAt", r.getSubmittedAt());
                    return row;
                }).toList());
    }

    @GetMapping("/consent/current")
    // A patient must be able to read what they are asked to accept. They hold
    // consent.accept but not consent.read, so this refused them.
    @PreAuthorize("hasAnyAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_READ, "
            + "T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_ACCEPT)")
    @Operation(
            summary = "The consent text in force",
            description = """
                    The published version, to display before asking the patient to accept.

                    **Returns 400 if none is published.** The seeded text is a placeholder
                    and is deliberately left as a draft, because a patient must not consent
                    to a note that says "FNPH to supply the terms".

                    **Requires** `consent.read`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current text."),
            @ApiResponse(responseCode = "400", description = "No published version.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> currentConsent() {
        ConsentDocument document = triageService.activeConsent("FNPH_PATIENT");
        return ResponseEntity.ok(Map.of(
                "version", document.getVersion(),
                "title", document.getTitle(),
                "body", document.getBody()));
    }

    @GetMapping("/consent/centre")
    // The text a centre reads to the patient before submitting a referral. The
    // patient document above is the FNPH_PATIENT one; centres had no way to
    // fetch theirs, so the version they recorded was whatever they typed.
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_REFERRAL_CREATE)")
    public ResponseEntity<Map<String, Object>> centreConsent() {
        ConsentDocument document = triageService.activeConsent("CENTRE");
        return ResponseEntity.ok(Map.of(
                "version", document.getVersion(),
                "title", document.getTitle(),
                "body", document.getBody()));
    }

    @PostMapping("/consent/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_ACCEPT)")
    @Operation(
            summary = "Record consent",
            description = """
                    Records that this patient accepted the version currently in force.

                    **Consent is per version, not per patient.** The version is recorded on
                    the acceptance, so changing the wording next year does not silently
                    rewrite what an existing patient agreed to, and a retired version stays
                    readable forever for the same reason.

                    Append-only. What somebody agreed to cannot be edited afterwards.

                    **Requires** `consent.accept`.
                    """)
    @ApiResponse(responseCode = "201", description = "Recorded.")
    public ResponseEntity<Map<String, Object>> accept(@RequestBody ConsentRequest request, HttpServletRequest http) {
        if (request.version() == null || !request.read())
            throw new TriageService.TriageException("Read the complete agreement before signing.");
        var principal = CurrentUser.require();
        var patient = patientRepository.findById(principal.getPatientId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No patient record on this account"));

        ConsentAcceptance acceptance = triageService.accept(patient, "FNPH_PATIENT",
                principal.getUsername(), null, clientIp(http), http.getHeader("User-Agent"),
                request.version(), request.signature(), request.declarations());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", acceptance.getPublicId(),
                "version", acceptance.getConsentVersion(),
                "acceptedAt", acceptance.getAcceptedAt()));
    }

    public record ConsentRequest(String version, boolean read, String signature, List<Boolean> declarations) {}

    @GetMapping("/consent/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_ACCEPT)")
    public Map<String, Object> receipt() {
        return triageService.currentConsent(CurrentUser.require().getPatientId())
                .filter(a -> a.getConsentVersion().equals(triageService.activeConsent("FNPH_PATIENT").getVersion()))
                .map(a -> Map.<String, Object>of("publicId", a.getPublicId(), "version", a.getConsentVersion(),
                        "acceptedAt", a.getAcceptedAt(), "signature", a.getTypedSignature() == null ? "" : a.getTypedSignature()))
                .orElse(Map.of());
    }

    // -----------------------------------------------------------------
    // Administration
    // -----------------------------------------------------------------

    @PostMapping("/admin/consent/{documentPublicId}/publish")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Operation(
            summary = "Publish a consent version",
            description = """
                    Publishes it and retires the version it replaces.

                    **Retired, not deleted.** Every acceptance against the old version still
                    has to be readable, because that is what those patients agreed to.

                    **Refused if the text still contains PLACEHOLDER.** A patient must not
                    consent to a note asking FNPH to supply the terms, and this is the check
                    that stops the seeded draft reaching production by accident.

                    **Requires** `consent.manage_versions`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Published, previous retired."),
            @ApiResponse(responseCode = "400", description = "The text still has placeholders.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> publishConsent(
            @PathVariable String documentPublicId) {
        ConsentDocument published = triageService.publishConsent(documentPublicId);
        return ResponseEntity.ok(Map.of(
                "version", published.getVersion(),
                "status", published.getStatus(),
                "effectiveFrom", published.getEffectiveFrom()));
    }

    @PostMapping("/admin/triage/{setPublicId}/publish")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Operation(
            summary = "Publish a triage question set",
            description = """
                    Publishes it and retires the previous set.

                    **Refused if any question still contains PLACEHOLDER.** The wording
                    decides which patients are turned away from a psychiatric service, so it
                    has to be FNPH's own.

                    **Requires** `consent.manage_versions`.
                    """)
    @ApiResponse(responseCode = "200", description = "Published.")
    public ResponseEntity<Map<String, Object>> publishQuestions(@PathVariable String setPublicId) {
        TriageQuestionSet published = triageService.publishQuestions(setPublicId);
        return ResponseEntity.ok(Map.of(
                "version", published.getVersion(),
                "status", published.getStatus(),
                "questions", published.getQuestions().size()));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
