package com.fnph.telepsychiatric.ehr.api;

import com.fnph.telepsychiatric.ehr.EhrVerificationService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/enrolment")
@RequiredArgsConstructor
@Tag(name = "Patient Enrolment")
public class EnrolmentController {

    private final EhrVerificationService verificationService;

    @PostMapping("/lookup")
    @SecurityRequirements
    @Operation(
            summary = "Step 1 — find my record and send me a code",
            description = """
                    Matches an EHR number against the active snapshot and sends a
                    six-digit code.

                    **The EHR number alone is never enough.** Supply the date of birth or
                    the last four digits of the phone the hospital holds. Either one plus
                    the number is accepted.

                    If a number alone returned a name, anyone could walk the range and
                    confirm that a named individual is a patient at a neuropsychiatric
                    hospital. That disclosure needs no account and no further step, which
                    makes it the likeliest attack on this service.

                    **The code goes to the number on file**, never to one supplied here.
                    Otherwise anyone who learned a number and a date of birth could point
                    the account at their own phone.

                    **Every failure returns the same message.** Wrong number, wrong
                    corroboration, inactive record and already-enrolled are
                    indistinguishable, so the form cannot be used to test whether an EHR
                    number is real. Staff can see the real outcome.

                    Rate limited two ways: five failures against one number and ten from
                    one address in thirty minutes.

                    The response includes `recordsAsAt` and `recordsAgeInDays`. **Show
                    them.** This is a snapshot of the offline hospital record, not a live
                    link, and a patient whose details changed last week needs to know why
                    they are not reflected.

                    **Public.**
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matched. A code has been sent.",
                    content = @Content(schema = @Schema(implementation = EnrolmentLookupResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "No match. The message is the same for every cause. Offer the "
                            + "help request.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EnrolmentLookupResponse> lookup(
            @Valid @RequestBody EnrolmentLookupRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(verificationService.lookup(request, clientIp(http),
                http.getHeader("User-Agent")));
    }

    @PostMapping("/complete")
    @SecurityRequirements
    @Operation(
            summary = "Steps 2 and 3 — confirm the code and choose a password",
            description = """
                    Verifies the code, creates the patient account and marks the contact
                    route confirmed.

                    The EHR number becomes the username, so all three sign-in routes
                    converge on one lookup.

                    **No second factor is required for patients.** Making someone enrol a
                    TOTP app to attend a psychiatric appointment is a barrier that stops
                    people attending, and a patient account cannot approve bookings, move
                    money or read anyone else's record. Contact verification and rate
                    limiting carry the weight instead.

                    Five wrong codes invalidates the attempt and enrolment starts again.

                    No tokens are returned; the patient signs in normally afterwards.

                    **Public.**
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Enrolled. Send the patient to sign in."),
            @ApiResponse(responseCode = "400",
                    description = "Wrong or expired code, too many attempts, or the password does "
                            + "not meet the policy.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> complete(@Valid @RequestBody CompleteEnrolmentRequest request,
                                         HttpServletRequest http) {
        verificationService.activate(request, clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/help")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Ask the hospital to verify me by hand",
            description = """
                    For a genuine patient the snapshot cannot match.

                    Needed because the snapshot is a snapshot. A patient registered last
                    week will not be in an extract taken last month, and refusing them
                    with no route forward would send them back for a physical visit over
                    an administrative gap.

                    The request goes to a queue worked by the Hub Coordinator, HIM and
                    ICT. **It creates no account and grants nothing**, and a request for a
                    number that does not exist looks exactly like one for a number that
                    does.

                    **Always returns 202**, including for a duplicate. A different reply
                    would reveal that a request already exists for that number.

                    **Public.**
                    """)
    @ApiResponse(responseCode = "202", description = "Request received.")
    public Map<String, String> help(@Valid @RequestBody VerificationHelpRequest request,
                                    HttpServletRequest http) {
        return Map.of("message", verificationService.requestVerification(request, clientIp(http)));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
