package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.clinical.Vitals;
import com.fnph.telepsychiatric.clinical.VitalsService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
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

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/clinical/vitals")
@RequiredArgsConstructor
@Tag(name = "Vitals")
public class VitalsController {

    private final VitalsService vitalsService;
    private final AppointmentRepository appointmentRepository;

    @PostMapping("/appointments/{appointmentPublicId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).VITALS_SUBMIT)")
    @Operation(
            summary = "Record vitals for an appointment",
            description = """
                    Mandatory before a consultation on the FNPH pathway.

                    **BMI is not a field.** It is computed from the height and weight you
                    send. A client that supplies its own can supply one that disagrees with
                    the two numbers next to it, and the clinician reading the screen has no
                    way to tell which is wrong.

                    **Implausible values are refused, not flagged.** A systolic pressure of
                    1200 is a typo, and a clinician who meets it mid-consultation has to
                    stop and ask rather than assess. The ranges are wide on purpose: they
                    catch a slipped decimal point, not an unusual patient.

                    Diastolic at or above systolic is refused as well, because the usual
                    cause is the two being entered the wrong way round.

                    `measurementSource` should say who took them. A patient-reported reading
                    and a nurse-measured one are different things.

                    **Requires** `vitals.submit`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded, with BMI derived.",
                    content = @Content(schema = @Schema(implementation = VitalsResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "A value is outside its plausible range, the pressures are "
                            + "swapped, or the time is in the future.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<VitalsResponse> record(
            @PathVariable String appointmentPublicId,
            @Valid @RequestBody VitalsRequest request) {

        Vitals vitals = vitalsService.record(appointmentPublicId,
                new VitalsService.VitalsEntry(
                        request.systolic(), request.diastolic(), request.heartRate(),
                        request.respiratoryRate(), request.temperature(), request.weightKg(),
                        request.heightCm(), request.bloodOxygen(), request.bloodGlucose(),
                        request.measuredAt(), request.measurementSource(), request.notes()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(vitals));
    }

    @PostMapping("/{vitalsPublicId}/verify")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).VITALS_VERIFY)")
    @Operation(
            summary = "Verify a vitals record",
            description = """
                    Records that a nurse checked the readings.

                    Worth doing explicitly: a clinician deciding on medication needs to know
                    whether a reading was measured by a nurse or reported by the patient.

                    **Requires** `vitals.verify`, held by Nursing.
                    """)
    @ApiResponse(responseCode = "200", description = "Verified.")
    public ResponseEntity<VitalsResponse> verify(@PathVariable String vitalsPublicId) {
        return ResponseEntity.ok(toResponse(vitalsService.verify(vitalsPublicId)));
    }

    @GetMapping("/appointments/{appointmentPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).VITALS_READ)")
    @Operation(
            summary = "Vitals for an appointment",
            description = """
                    Newest first. Pharmacy and laboratory hold `vitals.read` and use it
                    during review; the clinical note stays out of their reach.

                    **Requires** `vitals.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Records returned.")
    public ResponseEntity<List<VitalsResponse>> forAppointment(
            @PathVariable String appointmentPublicId) {
        var appointment = appointmentRepository.findByPublicId(appointmentPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such appointment"));
        return ResponseEntity.ok(vitalsService.forAppointment(appointment.getId())
                .stream().map(this::toResponse).toList());
    }

    private VitalsResponse toResponse(Vitals v) {
        return new VitalsResponse(
                v.getPublicId(), v.getBloodPressureSystolic(), v.getBloodPressureDiastolic(),
                v.getHeartRate(), v.getRespiratoryRate(), v.getTemperature(),
                v.getWeightKg(), v.getHeightCm(), v.getBmi(), v.getBloodOxygen(),
                v.getBloodGlucose(), v.getMeasuredAt(), v.getMeasurementSource(),
                v.getVerifiedAt(), v.getVerifiedBy(), v.getNotes());
    }

    @Schema(name = "VitalsRequest",
            description = "Measured readings. BMI is derived and must not be sent.")
    public record VitalsRequest(
            @Schema(example = "120") Integer systolic,
            @Schema(example = "80") Integer diastolic,
            @Schema(example = "72") Integer heartRate,
            @Schema(example = "16") Integer respiratoryRate,
            @Schema(description = "Degrees Celsius.", example = "36.8") Double temperature,
            @Schema(example = "70.5") Double weightKg,
            @Schema(example = "175.0") Double heightCm,
            @Schema(description = "SpO2 percentage.", example = "98") Integer bloodOxygen,
            @Schema(description = "mmol/L.", example = "5.4") Double bloodGlucose,
            @Schema(description = "When they were taken. Defaults to now, and cannot be in "
                    + "the future.", nullable = true)
            LocalDateTime measuredAt,
            @Schema(description = "Who took them.", example = "Nurse, clinic room 2")
            String measurementSource,
            @Schema(nullable = true) String notes
    ) {
    }

    @Schema(name = "Vitals", description = "A recorded set of readings.")
    public record VitalsResponse(
            String publicId,
            Integer systolic, Integer diastolic, Integer heartRate, Integer respiratoryRate,
            Double temperature, Double weightKg, Double heightCm,
            @Schema(description = "Computed from height and weight, never accepted from a "
                    + "client.", example = "23.0")
            Double bmi,
            Integer bloodOxygen, Double bloodGlucose,
            LocalDateTime measuredAt,
            String measurementSource,
            @Schema(description = "Null until a nurse verifies. A patient-reported reading "
                    + "and a measured one are different things.", nullable = true)
            LocalDateTime verifiedAt,
            @Schema(nullable = true) String verifiedBy,
            @Schema(nullable = true) String notes
    ) {
    }
}
