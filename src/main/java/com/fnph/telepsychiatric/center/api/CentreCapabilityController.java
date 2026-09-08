package com.fnph.telepsychiatric.center.api;

import com.fnph.telepsychiatric.center.*;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/centres/{centrePublicId}/capabilities")
@RequiredArgsConstructor
@Tag(name = "Administration — Centre Capabilities")
public class CentreCapabilityController {

    private final CenterRepository centreRepository;
    private final CentreCapabilityRepository capabilityRepository;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_READ)")
    @Operation(
            summary = "List a centre's optional local roles",
            description = """
                    Returns all three capabilities with their current state and the audit
                    around each: who activated or withdrew it, when, and why.

                    Every centre starts with all three disabled. The specification is
                    explicit that initial centre access is the Centre Hub Coordinator and
                    one Assistant only, and that these are activated later by the Central
                    Administrator after staffing and capability review.

                    A disabled capability means the corresponding role cannot be assigned
                    to anyone at that centre; account creation rejects it.

                    **Requires** `centre.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "All three capabilities returned.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CentreCapabilityResponse.class))))
    @Transactional(readOnly = true)
    public ResponseEntity<List<CentreCapabilityResponse>> list(
            @Parameter(description = "The centre's public identifier.", required = true)
            @PathVariable String centrePublicId) {

        Center centre = requireCentre(centrePublicId);
        return ResponseEntity.ok(
                capabilityRepository.findAllByCentreIdOrderByCapabilityAsc(centre.getId())
                        .stream().map(this::toResponse).toList());
    }

    @PutMapping("/{capability}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_CAPABILITY_MANAGE)")
    @Operation(
            summary = "Activate or withdraw an optional local role at a centre",
            description = """
                    Enabling allows the matching role (`CENTRE_PHARMACY`,
                    `CENTRE_LABORATORY`, `CENTRE_HIM`) to be assigned at this centre.
                    Disabling stops new assignments.

                    **Withdrawing does not remove access from anyone who already holds the
                    role.** That is deliberate: silently stripping a role from a working
                    account mid-shift would strand clinical work in progress with no
                    explanation to the person doing it. Deactivate the individual accounts
                    as a separate, visible decision.

                    The reason is mandatory. It records the staffing and capability review
                    the specification requires, and on withdrawal it distinguishes "the
                    pharmacist left" from "the centre failed an audit", which should not
                    look identical a year later.

                    **Requires** `centre_capability.manage`, held only by the Central
                    Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capability updated.",
                    content = @Content(schema = @Schema(implementation = CentreCapabilityResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `centre_capability.manage`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such centre.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<CentreCapabilityResponse> update(
            @PathVariable String centrePublicId,
            @Parameter(description = "Which capability.", example = "PHARMACY", required = true)
            @PathVariable CapabilityType capability,
            @Valid @RequestBody CentreCapabilityRequest request) {

        Center centre = requireCentre(centrePublicId);
        CentreCapability record = capabilityRepository
                .findByCentreIdAndCapability(centre.getId(), capability)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No " + capability + " capability row for that centre"));

        LocalDateTime now = LocalDateTime.now();
        String actor = CurrentUser.usernameOrSystem();

        if (Boolean.TRUE.equals(request.enabled())) {
            record.setIsEnabled(true);
            record.setEnabledAt(now);
            record.setEnabledBy(actor);
            record.setEnableReason(request.reason());
        } else {
            record.setIsEnabled(false);
            record.setDisabledAt(now);
            record.setDisabledBy(actor);
            record.setDisableReason(request.reason());
        }

        return ResponseEntity.ok(toResponse(capabilityRepository.save(record)));
    }

    private Center requireCentre(String publicId) {
        return centreRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No centre with id " + publicId));
    }

    private CentreCapabilityResponse toResponse(CentreCapability c) {
        return new CentreCapabilityResponse(
                c.getCapability().name(),
                switch (c.getCapability()) {
                    case PHARMACY -> "CENTRE_PHARMACY";
                    case LABORATORY -> "CENTRE_LABORATORY";
                    case HIM -> "CENTRE_HIM";
                },
                Boolean.TRUE.equals(c.getIsEnabled()),
                c.getEnabledAt(), c.getEnabledBy(), c.getEnableReason(),
                c.getDisabledAt(), c.getDisabledBy(), c.getDisableReason());
    }
}
