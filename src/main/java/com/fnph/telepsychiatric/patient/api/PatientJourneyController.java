package com.fnph.telepsychiatric.patient.api;

import com.fnph.telepsychiatric.patient.PatientJourneyService;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/patient/intake")
public class PatientJourneyController {
    private final PatientJourneyService journey;
    @GetMapping("/appointments/{publicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).APPOINTMENT_READ)")
    public PatientJourneyService.Intake forAppointment(@PathVariable String publicId) {
        return journey.forAppointment(publicId);
    }
    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_HOLD)")
    public PatientJourneyService.Draft current() { return journey.current(CurrentUser.require().getPatientId()); }
    @PutMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SLOT_HOLD)")
    public PatientJourneyService.Draft save(@RequestBody PatientJourneyService.Intake intake) {
        return journey.save(CurrentUser.require().getPatientId(), intake);
    }
}
