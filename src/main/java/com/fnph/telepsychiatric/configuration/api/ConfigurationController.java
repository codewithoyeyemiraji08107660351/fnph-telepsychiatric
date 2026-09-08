package com.fnph.telepsychiatric.configuration.api;

import com.fnph.telepsychiatric.configuration.ConfigurationChange;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.configuration.SystemConfiguration;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/configuration")
@RequiredArgsConstructor
@Tag(name = "Administration — Configuration")
public class ConfigurationController {

    private final ConfigurationService configurationService;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONFIG_READ)")
    @Operation(
            summary = "List governed settings",
            description = """
                    Fees, session timing, thresholds and validity periods, with the bounds
                    each is checked against.

                    Three of these look like one setting and are not.
                    `room_open_lead_minutes` is how early the join control activates.
                    `joining_grace_minutes` is how late a patient may be before being
                    flagged late. `no_show_cutoff_minutes` is when the link deactivates.
                    The source documents give three different numbers for what reads like a
                    single value, so they are kept apart here rather than merged into an
                    ambiguity.

                    Session length is per audience, so changing the Centre duration cannot
                    silently move FNPH appointments.

                    **Requires** `config.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Settings returned.",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConfigurationResponse.class))))
    public ResponseEntity<List<ConfigurationResponse>> list(
            @Parameter(description = "Restrict to one category. Omit for all.", example = "finance")
            @RequestParam(required = false) String category) {

        List<SystemConfiguration> configs = category == null
                ? configurationService.listAll()
                : configurationService.listByCategory(category);

        return ResponseEntity.ok(configs.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{key}/history")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONFIG_READ)")
    @Operation(
            summary = "Every change to one setting",
            description = """
                    Newest first, including the seeded initial value so history starts at
                    creation rather than at the first edit.

                    This is what settles a reconciliation dispute. Six months from now the
                    question is which consultation fee was in force on a given day and who
                    decided it, and the previous value plus the reason are the only things
                    that answer it.

                    **Requires** `config.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Change history returned.")
    public ResponseEntity<List<ConfigurationChangeResponse>> history(
            @Parameter(description = "The configuration key.", example = "consultation_fee_ngn",
                    required = true)
            @PathVariable String key) {
        return ResponseEntity.ok(configurationService.history(key).stream()
                .map(this::toChangeResponse).toList());
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONFIG_UPDATE)")
    @Operation(
            summary = "Change a setting",
            description = """
                    Validates the new value against the setting's type, bounds and permitted
                    values, records the change with the previous value and the reason, and
                    writes an audit entry.

                    Bounds are enforced because a consultation fee of zero or a session
                    length of four hours is a typo, and the moment to catch it is before it
                    reaches a patient-facing screen rather than in a report a month later.

                    Setting a value identical to the current one is accepted and records
                    nothing, so the history does not fill with entries an auditor has to
                    read and discard.

                    `effectiveFrom` lets a change be scheduled, for example a fee taking
                    effect at the start of the next month.

                    **Requires** `config.update`, held only by the Central Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Setting updated.",
                    content = @Content(schema = @Schema(implementation = ConfigurationResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Wrong type, outside bounds, not a permitted value, or the "
                            + "reason is too short.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `config.update`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such key.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ConfigurationResponse> update(
            @PathVariable String key,
            @Valid @RequestBody UpdateConfigurationRequest request,
            HttpServletRequest http) {

        String forwarded = http.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();

        return ResponseEntity.ok(toResponse(configurationService.update(
                key, request.value(), request.reason(), request.effectiveFrom(), ip)));
    }

    private ConfigurationResponse toResponse(SystemConfiguration c) {
        return new ConfigurationResponse(
                c.getConfigKey(),
                Boolean.TRUE.equals(c.getIsSensitive()) ? "********" : c.getConfigValue(),
                c.getValueType().name(), c.getCategory(), c.getDescription(),
                c.getMinValue(), c.getMaxValue(), c.getAllowedValues(),
                Boolean.TRUE.equals(c.getRequiresGovernance()), c.getEffectiveFrom());
    }

    private ConfigurationChangeResponse toChangeResponse(ConfigurationChange c) {
        return new ConfigurationChangeResponse(
                c.getConfigKey(), c.getPreviousValue(), c.getNewValue(),
                c.getReason(), c.getChangedBy(), c.getChangedAt(), c.getEffectiveFrom());
    }
}
