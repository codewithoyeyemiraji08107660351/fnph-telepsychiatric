package com.fnph.telepsychiatric.patient.api;
import com.fnph.telepsychiatric.patient.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/patient/profile")
public class PatientPortalProfileController {
    private final PatientPortalProfileRepository profiles;
    private final PatientRepository patients;
    public record Profile(@Size(max=30) @Pattern(regexp="[+0-9 ()-]*") String phone,
                          @Email @Size(max=254) String email, @Size(max=300) String location) {}
    @GetMapping @Transactional(readOnly=true)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_READ_OWN) and "
            + "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PROFILE_READ_OWN)")
    public Profile get() {
        Long id=CurrentUser.require().getPatientId();
        return profiles.findByPatientId(id).map(p -> new Profile(p.getPhone(),p.getEmail(),p.getLocation()))
                .orElse(new Profile("","",""));
    }
    @PutMapping @Transactional
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PATIENT_READ_OWN) and "
            + "hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PROFILE_UPDATE_OWN)")
    public Profile save(@Valid @RequestBody Profile input) {
        Long id=CurrentUser.require().getPatientId();
        patients.findByIdForUpdate(id).orElseThrow();
        var profile=profiles.findByPatientId(id).orElseGet(PatientPortalProfile::new);
        profile.setPatientId(id); profile.setPhone(input.phone()); profile.setEmail(input.email()); profile.setLocation(input.location());
        profiles.save(profile);
        return input;
    }
}
