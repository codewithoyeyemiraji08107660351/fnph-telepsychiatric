package com.fnph.telepsychiatric.centre.api;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreBookingService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.Users;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Centre of Excellence — Patients and Booking")
public class CentreBookingController {

    private final CentreBookingService bookingService;
    private final CentreAppointmentRepository appointmentRepository;
    private final CenterRepository centreRepository;
    private final UserRepository userRepository;

    @PostMapping("/api/v1/centres/me/patients")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_PATIENT_CREATE)")
    @Operation(
            summary = "Register a centre-local patient",
            description = """
                    Creates a patient record belonging to your centre.

                    **A centre patient is never an FNPH patient.** Even when they also hold
                    an FNPH record, the offline hospital record is not linked or retrieved.
                    Anything the consulting doctor knows comes from the referral you write.

                    The local identifier is generated from your centre code if you do not
                    supply one. It is unique per centre, not globally: two centres numbering
                    their patients from one is normal.

                    **Requires** `centre_patient.create`.
                    """)
    @ApiResponse(responseCode = "201", description = "Registered.")
    public ResponseEntity<Map<String, Object>> registerPatient(
            @Valid @RequestBody RegisterPatientRequest request) {

        var centre = centreRepository.findById(CurrentUser.require().getCentreId())
                .orElseThrow(() -> new EntityNotFoundException("No centre on this account"));

        CentrePatient details = new CentrePatient();
        details.setCentrePatientId(request.centrePatientId());
        details.setFirstName(request.firstName());
        details.setLastName(request.lastName());
        details.setMiddleName(request.middleName());
        details.setDateOfBirth(request.dateOfBirth());
        details.setGender(request.gender());
        details.setPhoneNumber(request.phoneNumber());
        details.setEmail(request.email());
        details.setAddress(request.address());
        details.setFnphEhrNumber(request.fnphEhrNumber());

        CentrePatient saved = bookingService.registerPatient(centre, details);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", saved.getPublicId(),
                "centrePatientId", saved.getCentrePatientId(),
                "name", saved.getFirstName() + " " + saved.getLastName()));
    }

    @GetMapping("/api/v1/centres/me/booking/times")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_REQUEST)")
    @Operation(
            summary = "Times available on the centre schedule",
            description = """
                    Drawn from the **centre** schedule, which the FNPH Hub Coordinator
                    publishes separately from the patient one.

                    Separate schedules mean a busy morning of patient bookings cannot
                    consume every centre slot, or the reverse.

                    **Requires** `appointment.request`.
                    """)
    @ApiResponse(responseCode = "200", description = "Times returned.")
    public ResponseEntity<List<Map<String, Object>>> times(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(bookingService.availableSlots(date).stream().map(s -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("slotPublicId", s.getPublicId());
            row.put("startAt", s.getStartAt());
            row.put("endAt", s.getEndAt());
            return row;
        }).toList());
    }

    @PostMapping("/api/v1/centres/me/booking/request")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_REQUEST)")
    @Operation(
            summary = "Request a consultation against a submitted referral",
            description = """
                    Books the time and sends the request to the FNPH Hub Coordinator.

                    **There is no payment step and no held state.** A centre does not pay
                    per booking; the prepaid wallet is debited when FNPH approves. So the
                    request goes straight to `AWAITING_APPROVAL` and the slot is taken
                    immediately.

                    The referral must be `SUBMITTED`, which means consent has been recorded
                    for that referral.

                    **Requires** `appointment.request`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Requested."),
            @ApiResponse(responseCode = "400",
                    description = "Referral not submitted, or another centre took the time.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> request(
            @Parameter(required = true) @RequestParam String referralPublicId,
            @Parameter(required = true) @RequestParam String slotPublicId) {

        CentreAppointment appointment = bookingService.requestAppointment(
                referralPublicId, slotPublicId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", appointment.getPublicId(),
                "reference", appointment.getReference(),
                "appointmentDate", appointment.getAppointmentDate(),
                "status", appointment.getStatus().name()));
    }

    // -----------------------------------------------------------------
    // FNPH side
    // -----------------------------------------------------------------

    @GetMapping("/api/v1/hub/centre-approvals")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    @Operation(
            summary = "Centre consultation requests awaiting a decision",
            description = """
                    Across every centre, earliest first.

                    Approving debits that centre's prepaid wallet. If the wallet cannot
                    cover the booking, approval is refused before anyone is assigned, so the
                    refusal is about funding rather than a half-built appointment somebody
                    then has to unpick.

                    **Requires** `appointment.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<Map<String, Object>>> centreQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(appointmentRepository
                .findAllByStatusOrderByAppointmentDateAsc(Status.AWAITING_APPROVAL,
                        PageRequest.of(page, Math.min(size, 200)))
                .map(a -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", a.getPublicId());
                    row.put("reference", a.getReference());
                    row.put("centre", a.getCentre().getName());
                    row.put("patient", a.getCentrePatient().getFirstName() + " "
                            + a.getCentrePatient().getLastName());
                    row.put("centrePatientId", a.getCentrePatient().getCentrePatientId());
                    row.put("appointmentDateTime", a.getAppointmentDate());
                    row.put("referralReason", a.getReferral() == null
                            ? null : a.getReferral().getReferralReason());
                    return row;
                }).getContent());
    }

    @PostMapping("/api/v1/hub/centre-approvals/{appointmentPublicId}/approve")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_APPROVE)")
    @Operation(
            summary = "Approve a centre request and assign the team",
            description = """
                    Checks the doctor's availability and the room, **debits the centre
                    wallet**, assigns the team and notifies both ends.

                    Funding is checked before anything is assigned. A booking approved
                    against an empty wallet is a consultation the hospital delivers and
                    cannot account for.

                    The centre is told the consultation is confirmed and is **not** told the
                    amount. Centres do not see wallet figures.

                    **Requires** `appointment.approve`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved and wallet debited."),
            @ApiResponse(responseCode = "400",
                    description = "Insufficient wallet balance, doctor unavailable, or the "
                            + "room is not in service.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String appointmentPublicId,
            @RequestParam String doctorPublicId,
            @RequestParam String roomPublicId,
            @RequestParam(required = false) String pharmacistPublicId,
            @RequestParam(required = false) String laboratoryPublicId,
            @RequestParam(required = false) String himPublicId,
            @RequestParam(required = false) String notes) {

        CentreAppointment approved = bookingService.approve(appointmentPublicId,
                requireUser(doctorPublicId), optionalUser(pharmacistPublicId),
                optionalUser(laboratoryPublicId), optionalUser(himPublicId),
                roomPublicId, notes);

        return ResponseEntity.ok(Map.of(
                "reference", approved.getReference(),
                "status", approved.getStatus().name(),
                "room", approved.getRoom(),
                "walletDebitedAt", String.valueOf(approved.getWalletDebitedAt())));
    }

    @PostMapping("/api/v1/hub/centre-approvals/{appointmentPublicId}/return")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_REJECT)")
    @Operation(
            summary = "Return a request to the centre for more information",
            description = """
                    Releases the slot and sends the referral back as `RETURNED` so the centre
                    can add what is missing and resubmit rather than starting again.

                    **No wallet movement**, because approval never happened.

                    The reason goes to the centre. Write it so a coordinator knows what to
                    add.

                    **Requires** `appointment.reject`.
                    """)
    @ApiResponse(responseCode = "204", description = "Returned to the centre.")
    public ResponseEntity<Void> returnToCentre(@PathVariable String appointmentPublicId,
                                               @RequestParam String reason) {
        bookingService.returnToCentre(appointmentPublicId, reason);
        return ResponseEntity.noContent().build();
    }

    private Users requireUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such user"));
    }

    private Users optionalUser(String publicId) {
        return publicId == null || publicId.isBlank()
                ? null : userRepository.findByPublicId(publicId).orElse(null);
    }

    @Schema(name = "RegisterCentrePatientRequest", description = "A centre-local patient.")
    public record RegisterPatientRequest(
            @Size(max = 50)
            @Schema(description = "Your local identifier. Generated from your centre code "
                    + "if omitted.", nullable = true)
            String centrePatientId,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 100) String middleName,
            @NotNull @Past LocalDate dateOfBirth,
            @Size(max = 20) String gender,
            @NotBlank @Size(max = 20) String phoneNumber,
            @Size(max = 100) String email,
            @Size(max = 500) String address,
            @Size(max = 50)
            @Schema(description = """
                    If the patient also holds an FNPH record. **Narrative only.** The \
                    offline FNPH record is never linked or retrieved for a centre patient, \
                    so this is text for the clinician to read, not a key.
                    """, nullable = true)
            String fnphEhrNumber
    ) {
    }
}
