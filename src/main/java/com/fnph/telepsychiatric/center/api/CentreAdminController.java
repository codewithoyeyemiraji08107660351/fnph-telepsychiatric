package com.fnph.telepsychiatric.center.api;

import com.fnph.telepsychiatric.center.*;
import com.fnph.telepsychiatric.centre.CentreReferralService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.payment.Wallet;
import com.fnph.telepsychiatric.payment.WalletRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.upload.FileUpload;
import com.fnph.telepsychiatric.upload.FileUploadRepository;
import com.fnph.telepsychiatric.upload.ScanStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.fnph.telepsychiatric.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Centre administration, the centre's own view of itself, and file quarantine.
 *
 * Grouped because they are the remaining small surfaces and splitting them into
 * three controllers with two endpoints each would be worse to maintain than one
 * with a clear shape.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Administration — Centres and Quarantine")
public class CentreAdminController {

    private final CenterRepository centreRepository;
    private final CentreCapabilityRepository capabilityRepository;
    private final CentrePatientRepository centrePatientRepository;
    private final CentreReferralService referralService;
    private final WalletRepository walletRepository;
    private final FileUploadRepository uploadRepository;

    @PostMapping("/admin/centres")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_CREATE)")
    @Operation(
            summary = "Add a Centre of Excellence",
            description = """
                    Creates the centre in `SETUP` with a zero-balance wallet and all three
                    optional capabilities disabled.

                    **`SETUP`, not `ACTIVE`, deliberately.** A centre with no coordinator has
                    nobody to receive a released care bundle, so a referral from it would
                    strand clinical output. Activation is a separate decision once staffing
                    is in place.

                    The 23 LGA centres are already seeded. This is for a new one.

                    **Requires** `centre.create`, held by the Central Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created in SETUP."),
            @ApiResponse(responseCode = "400", description = "That code is already in use.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> createCentre(
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam(required = false) String lga,
            @RequestParam(required = false) String address) {

        centreRepository.findByCode(code).ifPresent(existing -> {
            throw new IllegalArgumentException("A centre with code " + code + " already exists");
        });

        Center centre = new Center();
        centre.setCode(code);
        centre.setName(name);
        centre.setLga(lga);
        centre.setAddress(address);
        centre.setStatus(CentreStatus.SETUP);
        centre.setIsActive(false);
        Center saved = centreRepository.save(centre);

        // Zero balance, so nothing can be approved against it until Finance
        // credits it. Every centre has a wallet from the moment it exists.
        Wallet wallet = new Wallet();
        wallet.setCentre(saved);
        wallet.setBalance(BigDecimal.ZERO);
        walletRepository.save(wallet);

        // A row per capability, all disabled. A missing row and a disabled row
        // would mean the same thing and read differently.
        for (CapabilityType type : CapabilityType.values()) {
            CentreCapability capability = new CentreCapability();
            capability.setCentre(saved);
            capability.setCapability(type);
            capability.setIsEnabled(false);
            capabilityRepository.save(capability);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "publicId", saved.getPublicId(),
                "code", saved.getCode(),
                "status", saved.getStatus().name()));
    }

    @PostMapping("/admin/centres/{centrePublicId}/activate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_ACTIVATE)")
    @Operation(
            summary = "Activate or suspend a centre",
            description = """
                    `ACTIVE` lets the centre submit referrals. `SUSPENDED` stops new ones and
                    leaves staff, history and existing bookings intact.

                    Suspension is reversible on purpose. It is what you use while a problem
                    is investigated, and losing a centre's records to make it stop booking
                    would be the wrong trade.

                    A reason is required for suspension. "The pharmacist left" and "the
                    centre failed an audit" should not look identical a year later.

                    **Requires** `centre.activate`.
                    """)
    @ApiResponse(responseCode = "200", description = "Status changed.")
    @Transactional
    public ResponseEntity<Map<String, Object>> setStatus(
            @PathVariable String centrePublicId,
            @Parameter(example = "ACTIVE", required = true) @RequestParam CentreStatus status,
            @RequestParam(required = false) String reason) {

        if (status == CentreStatus.SUSPENDED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Say why the centre is being suspended");
        }

        Center centre = requireCentre(centrePublicId);
        centre.setStatus(status);
        centre.setIsActive(status == CentreStatus.ACTIVE);

        LocalDateTime now = LocalDateTime.now();
        if (status == CentreStatus.ACTIVE) {
            centre.setActivatedAt(now);
            centre.setActivatedBy(CurrentUser.usernameOrSystem());
        } else if (status == CentreStatus.SUSPENDED) {
            centre.setSuspendedAt(now);
            centre.setSuspendedBy(CurrentUser.usernameOrSystem());
            centre.setSuspendReason(reason);
        }
        centreRepository.save(centre);

        return ResponseEntity.ok(Map.of("code", centre.getCode(), "status", status.name()));
    }

    @PutMapping("/admin/centres/{centrePublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_UPDATE)")
    @Operation(summary = "Correct a centre's details",
            description = "Name, LGA, address and contact. The code is not editable: it "
                    + "appears in centre-local patient identifiers already issued.\n\n"
                    + "**Requires** `centre.update`.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateCentre(
            @PathVariable String centrePublicId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lga,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String contactPhone) {

        Center centre = requireCentre(centrePublicId);
        if (name != null && !name.isBlank()) {
            centre.setName(name);
        }
        if (lga != null) {
            centre.setLga(lga);
        }
        if (address != null) {
            centre.setAddress(address);
        }
        if (contactPhone != null) {
            centre.setPhoneNumber(contactPhone);
        }
        centreRepository.save(centre);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @GetMapping("/centres/me")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_READ_OWN)")
    @Operation(
            summary = "My centre",
            description = """
                    Your own centre's details, status and which optional roles are activated.

                    **No wallet balance.** Centres see consultation and utilisation counts;
                    Finance credits the wallet from programme funding and holds that view. If
                    a booking is refused for funding, FNPH will tell you.

                    A `SETUP` status means referrals cannot be submitted yet.

                    **Requires** `centre.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Centre returned.")
    public ResponseEntity<Map<String, Object>> myCentre() {
        Center centre = centreRepository.findById(CurrentUser.require().getCentreId())
                .orElseThrow(() -> new EntityNotFoundException("No centre on this account"));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("publicId", centre.getPublicId());
        body.put("code", centre.getCode());
        body.put("name", centre.getName());
        body.put("lga", centre.getLga());
        body.put("status", centre.getStatus().name());
        body.put("capabilities", capabilityRepository
                .findAllByCentreIdOrderByCapabilityAsc(centre.getId()).stream()
                .map(c -> Map.of("capability", c.getCapability().name(),
                        "enabled", Boolean.TRUE.equals(c.getIsEnabled())))
                .toList());
        body.put("utilisation", referralService.utilisation());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/centres/me/patients")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_PATIENT_READ)")
    @Operation(
            summary = "My centre's patients",
            description = """
                    Scoped to your centre by the tenant filter, not by a parameter. A patient
                    at another centre is not visible by any route, including by identifier.

                    **Requires** `centre_patient.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Patients returned.")
    public ResponseEntity<List<Map<String, Object>>> myPatients(
            @RequestParam(required = false) String term) {

        Long centreId = TenantContext.requireCentreId();

        var patients = (term == null || term.isBlank())
                ? centrePatientRepository.findAllByCentreIdAndIsActiveTrueOrderByLastNameAsc(centreId)
                : centrePatientRepository.search(centreId, term.trim());

        return ResponseEntity.ok(patients.stream().map(p -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("publicId", p.getPublicId());
            row.put("centrePatientId", p.getCentrePatientId());
            row.put("name", p.getFirstName() + " " + p.getLastName());
            row.put("dateOfBirth", p.getDateOfBirth());
            row.put("phoneNumber", p.getPhoneNumber());
            return row;
        }).toList());
    }

    @PutMapping("/centres/me/patients/{centrePatientPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CENTRE_PATIENT_UPDATE)")
    @Operation(summary = "Correct a centre patient's details",
            description = "Contact details only. **Requires** `centre_patient.update`.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateCentrePatient(
            @PathVariable String centrePatientPublicId,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String address) {

        var patient = centrePatientRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), centrePatientPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such patient at this centre"));

        if (phoneNumber != null) {
            patient.setPhoneNumber(phoneNumber);
        }
        if (email != null) {
            patient.setEmail(email);
        }
        if (address != null) {
            patient.setAddress(address);
        }
        centrePatientRepository.save(patient);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    // -----------------------------------------------------------------
    // Quarantine
    // -----------------------------------------------------------------

    @PostMapping("/admin/uploads/{uploadPublicId}/quarantine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_QUARANTINE)")
    @Operation(
            summary = "Quarantine an uploaded file",
            description = """
                    Marks a file infected or suspicious and stops it being served.

                    **No scanner is wired yet**, so this is currently a manual action for
                    ICT when a file is reported. When ClamAV is added it will call the same
                    path, which is why the state exists on the upload rather than being a
                    flag invented at scan time.

                    The file is not deleted. A quarantined file is evidence, and removing it
                    would destroy the only copy of what was actually uploaded.

                    **Requires** `upload.quarantine`, held by ICT and the Central
                    Administrator.
                    """)
    @ApiResponse(responseCode = "204", description = "Quarantined.")
    @Transactional
    public ResponseEntity<Void> quarantine(@PathVariable String uploadPublicId,
                                           @RequestParam String reason) {
        FileUpload upload = uploadRepository.findByPublicId(uploadPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such file"));

        upload.setScanStatus(ScanStatus.QUARANTINED);
        upload.setScanResult(reason);
        upload.setScannedAt(LocalDateTime.now());
        uploadRepository.save(upload);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/uploads/quarantined")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).UPLOAD_QUARANTINE)")
    @Operation(summary = "Quarantined files",
            description = "Kept rather than deleted, because a quarantined file is evidence.\n\n"
                    + "**Requires** `upload.quarantine`.")
    @ApiResponse(responseCode = "200", description = "Files returned.")
    public ResponseEntity<List<Map<String, Object>>> quarantined() {
        return ResponseEntity.ok(uploadRepository
                .findAllByScanStatusOrderByUploadedAtDesc(ScanStatus.QUARANTINED).stream()
                .map(u -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", u.getPublicId());
                    row.put("originalFileName", u.getOriginalFileName());
                    row.put("uploadedBy", u.getUploadedBy());
                    row.put("uploadedAt", u.getUploadedAt());
                    row.put("scanResult", u.getScanResult());
                    return row;
                }).toList());
    }

    private Center requireCentre(String publicId) {
        return centreRepository.findByPublicId(publicId)
                .orElseThrow(() -> new EntityNotFoundException("No such centre"));
    }
}
