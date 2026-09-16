package com.fnph.telepsychiatric.publicinfo.api;

import com.fnph.telepsychiatric.configuration.ConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Public information")
public class PublicInfoController {

    private static final List<String> PUBLIC_KEYS = List.of(
            "clinical_emergency_number", "helpdesk_email", "consultation_fee_ngn",
            "session_minutes_fnph", "joining_grace_minutes", "no_show_cutoff_minutes",
            "cancellation_notice_hours", "prescription_validity_days",
            "session_inactivity_timeout_minutes");

    private final ConfigurationService configurationService;

    @GetMapping("/settings")
    @SecurityRequirements
    @Operation(summary = "Non-sensitive settings for public pages",
            description = "Fixed allow-list. Cached for five minutes. Public.")
    public ResponseEntity<Map<String, String>> settings() {
        Map<String, String> body = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            try { body.put(key, configurationService.getString(key)); }
            catch (RuntimeException e) { log.warn("Public setting {} unavailable: {}", key, e.getMessage()); }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }
}