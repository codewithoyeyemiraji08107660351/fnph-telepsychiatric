package com.fnph.telepsychiatric.clinical.api;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.clinical.*;
import com.fnph.telepsychiatric.consultation.ConsultationRepository;
import com.fnph.telepsychiatric.document.IssuedDocumentRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reading clinical output, and the two work queues that had no endpoint.
 *
 * Split from the authoring controller because reading and writing have
 * different permissions and different audiences: a patient reads their own
 * prescription and cannot write one, a pharmacist reads the prescription and
 * not the note behind it.
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
@Tag(name = "Clinical Records")
public class ClinicalReadController {

    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;
    private final ConsultationRepository consultationRepository;
    private final AppointmentRepository appointmentRepository;
    private final IssuedDocumentRepository documentRepository;
    private final WorkQueueService workQueueService;
    private final VitalsService vitalsService;

    @GetMapping("/prescriptions/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_READ_OWN)")
    @Operation(
            summary = "My prescriptions",
            description = """
                    Newest first, including expired ones.

                    **Show the expired ones.** A patient whose prescription has lapsed needs
                    to see that it lapsed, not find it missing and assume the system lost it.

                    A prescription in `PENDING_REVIEW` is with the pharmacy and not yet
                    released. Say so rather than showing nothing.

                    **Requires** `prescription.read_own`.
                    """)
    @ApiResponse(responseCode = "200", description = "Prescriptions returned.")
    public ResponseEntity<List<Map<String, Object>>> myPrescriptions() {
        return ResponseEntity.ok(prescriptionRepository
                .findAllByPatientIdOrderByCreatedAtDesc(CurrentUser.require().getPatientId())
                .stream().map(this::prescriptionRow).toList());
    }

    @GetMapping("/investigations/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).INVESTIGATION_READ_OWN)")
    @Operation(summary = "My investigation requests",
            description = "Newest first, including expired. **Requires** `investigation.read_own`.")
    @ApiResponse(responseCode = "200", description = "Requests returned.")
    public ResponseEntity<List<Map<String, Object>>> myInvestigations() {
        return ResponseEntity.ok(investigationRepository
                .findAllByPatientIdOrderByCreatedAtDesc(CurrentUser.require().getPatientId())
                .stream().map(this::investigationRow).toList());
    }

    @GetMapping("/follow-ups/mine")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FOLLOW_UP_READ_OWN)")
    @Operation(summary = "My follow-up recommendations",
            description = "What the clinician advised. **Requires** `follow_up.read_own`.")
    @ApiResponse(responseCode = "200", description = "Recommendations returned.")
    public ResponseEntity<List<Map<String, Object>>> myFollowUps() {
        return ResponseEntity.ok(followUpRepository
                .findAllByPatientIdOrderByCreatedAtDesc(CurrentUser.require().getPatientId())
                .stream().map(f -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", f.getPublicId());
                    row.put("recommendation", f.getRecommendation());
                    row.put("reviewInterval", f.getReviewInterval());
                    row.put("preferredDate", f.getPreferredDate());
                    row.put("createdAt", f.getCreatedAt());
                    return row;
                }).toList());
    }

    @GetMapping("/prescriptions/{prescriptionPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).PRESCRIPTION_READ)")
    @Operation(
            summary = "One prescription",
            description = """
                    For clinical and pharmacy staff.

                    **The clinical note is not included and is not reachable from here.**
                    Pharmacy sees the prescription, the clinical information the doctor
                    supplied for them, and the patient's identity. That boundary is in the
                    permission matrix and in what this endpoint loads.

                    **Requires** `prescription.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Prescription returned.")
    public ResponseEntity<Map<String, Object>> prescription(
            @PathVariable String prescriptionPublicId) {
        return ResponseEntity.ok(prescriptionRow(prescriptionRepository
                .findByPublicId(prescriptionPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such prescription"))));
    }

    @GetMapping("/investigations/{investigationPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).INVESTIGATION_READ)")
    @Operation(summary = "One investigation request",
            description = "Same boundary as a prescription: no clinical note. "
                    + "**Requires** `investigation.read`.")
    @ApiResponse(responseCode = "200", description = "Request returned.")
    public ResponseEntity<Map<String, Object>> investigation(
            @PathVariable String investigationPublicId) {
        return ResponseEntity.ok(investigationRow(investigationRepository
                .findByPublicId(investigationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such request"))));
    }

    @GetMapping("/follow-ups/{followUpPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FOLLOW_UP_READ)")
    @Operation(summary = "One follow-up recommendation",
            description = "**Requires** `follow_up.read`.")
    @ApiResponse(responseCode = "200", description = "Recommendation returned.")
    public ResponseEntity<Map<String, Object>> followUp(@PathVariable String followUpPublicId) {
        var f = followUpRepository.findByPublicId(followUpPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such recommendation"));
        return ResponseEntity.ok(Map.of(
                "publicId", f.getPublicId(),
                "recommendation", f.getRecommendation(),
                "reviewInterval", String.valueOf(f.getReviewInterval())));
    }

    @GetMapping("/consultations/{consultationPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSULTATION_READ)")
    @Operation(
            summary = "A consultation and how it went",
            description = """
                    Timing, modality, attendance, outcome and how it ended.

                    **The clinical note is not here.** It has its own permission and its own
                    endpoint, so a role that needs to know a session happened does not
                    thereby get to read what was said in it.

                    `connectionIssues` and the termination reason are what answer "the call
                    kept dropping" with something other than one person's word.

                    **Requires** `consultation.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Consultation returned.")
    public ResponseEntity<Map<String, Object>> consultation(
            @PathVariable String consultationPublicId) {

        var c = consultationRepository.findByPublicId(consultationPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such consultation"));

        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", c.getPublicId());
        row.put("scheduledStartAt", c.getScheduledStartAt());
        row.put("scheduledEndAt", c.getScheduledEndAt());
        row.put("startedAt", c.getStartedAt());
        row.put("endedAt", c.getEndedAt());
        row.put("modality", c.getModality() == null ? null : c.getModality().name());
        row.put("outcome", c.getOutcome() == null ? null : c.getOutcome().name());
        row.put("identityConfirmed", c.getIdentityConfirmed());
        row.put("doctorJoinedAt", c.getDoctorJoinedAt());
        row.put("patientJoinedAt", c.getPatientJoinedAt());
        row.put("connectionIssues", c.getConnectionIssues());
        row.put("terminationReason", c.getTerminationReason() == null
                ? null : c.getTerminationReason().name());
        row.put("safetyActionTaken", c.getSafetyActionTaken());
        return ResponseEntity.ok(row);
    }

    // -----------------------------------------------------------------
    // Work queues
    // -----------------------------------------------------------------

    @GetMapping("/queues/nursing")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_NURSING)")
    @Operation(
            summary = "The nursing queue",
            description = """
                    Confirmed appointments assigned to you, soonest first, with whether
                    vitals have been recorded.

                    Vitals are mandatory before a consultation on the FNPH pathway, so
                    `vitalsRecorded` false on an appointment starting shortly is the thing
                    to act on.

                    **Requires** `queue.nursing`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<Map<String, Object>>> nursingQueue() {
        return ResponseEntity.ok(queueFor("NURSE"));
    }

    @GetMapping("/queues/him")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).QUEUE_HIM)")
    @Operation(
            summary = "The Health Information Management queue",
            description = """
                    Confirmed appointments assigned to you for offline record retrieval,
                    soonest first.

                    HIM pulls the paper record so the clinician has it during the session.
                    The offline EHR is authoritative and is not integrated, which is exactly
                    why this queue exists rather than the system fetching it.

                    **Requires** `queue.him`.
                    """)
    @ApiResponse(responseCode = "200", description = "Queue returned.")
    public ResponseEntity<List<Map<String, Object>>> himQueue() {
        return ResponseEntity.ok(queueFor("HIM"));
    }

    @GetMapping("/documents/{issueNumber}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).DOCUMENT_READ)")
    @Operation(
            summary = "Look up an issued document by its issue number",
            description = """
                    For staff answering a call about a document a patient is holding.

                    The issue number is what a patient or a pharmacist reads out, so it is
                    the key here rather than an internal identifier.

                    Returns the state and the dates, not the clinical content. A member of
                    staff confirming a document is genuine does not need the medication.

                    **Requires** `document.read`.
                    """)
    @ApiResponse(responseCode = "200", description = "Document returned.")
    public ResponseEntity<Map<String, Object>> documentByIssueNumber(
            @PathVariable String issueNumber) {

        var d = documentRepository.findByIssueNumber(issueNumber)
                .orElseThrow(() -> new EntityNotFoundException("No document with that number"));

        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("issueNumber", d.getIssueNumber());
        row.put("documentType", d.getDocumentType().name());
        row.put("status", d.effectiveStatus(java.time.LocalDateTime.now()).name());
        row.put("issuedAt", d.getIssuedAt());
        row.put("expiresAt", d.getExpiresAt());
        row.put("downloadCount", d.getDownloadCount());
        row.put("maxDownloads", d.getMaxDownloads());
        row.put("revokedReason", d.getRevokedReason());
        return ResponseEntity.ok(row);
    }

    @GetMapping("/queues/doctor")
    @PreAuthorize("""
        hasAuthority(
            T(com.fnph.telepsychiatric.authz.Permissions)
                .CONSULTATION_JOIN_AS_DOCTOR
        )
        """)
    @Operation(
            summary = "Your consultations",
            description = """
                Approved and in-progress appointments assigned to the
                authenticated doctor, soonest first.

                No clinical note, prescription or investigation content
                is returned by this endpoint.
                """)
    @ApiResponse(
            responseCode = "200",
            description = "Your consultations, soonest first.")
    public ResponseEntity<List<Map<String, Object>>> doctorQueue() {

        Long userId = CurrentUser.require().getUserId();

        List<Appointment> appointments =
                appointmentRepository.findDoctorQueue(
                        userId,
                        List.of(Status.IN_PROGRESS, Status.APPROVED));

        List<Map<String, Object>> rows = appointments.stream()
                .map(a -> {

                    Map<String, Object> row = new LinkedHashMap<>();

                    row.put("appointmentPublicId", a.getPublicId());
                    row.put("reference", a.getReference());
                    row.put("status", a.getStatus().name());
                    row.put("appointmentDate", a.getAppointmentDate());
                    row.put("scheduledEndAt", a.getScheduledEndAt());
                    row.put("room", a.getRoom());

                    row.put(
                            "patientName",
                            a.getPatient().getFirstName()
                                    + " "
                                    + a.getPatient().getLastName()
                    );

                    row.put(
                            "ehrNumber",
                            a.getPatient().getEhrNumber()
                    );

                    row.put(
                            "nursingState",
                            workQueueService
                                    .stateOf(
                                            a,
                                            WorkQueueService.Queue.NURSING)
                                    .name()
                    );

                    row.put(
                            "himState",
                            workQueueService
                                    .stateOf(
                                            a,
                                            WorkQueueService.Queue.HIM)
                                    .name()
                    );

                    Set<Long> appointmentIds = appointments.stream()
                            .map(Appointment::getId)
                            .collect(Collectors.toSet());

                    Set<Long> appointmentsWithVitals =
                            vitalsService.appointmentsWithVitals(appointmentIds);

                    return row;
                })
                .toList();

        return ResponseEntity.ok(rows);
    }

    private List<Map<String, Object>> queueFor(String role) {
        Long userId = CurrentUser.require().getUserId();

        return appointmentRepository
                .findAllByStatusOrderByAppointmentDateAsc(Status.APPROVED,
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .stream()
                .filter(a -> switch (role) {
                    case "NURSE" -> a.getNurse() != null && a.getNurse().getId().equals(userId);
                    case "HIM" -> a.getHimOfficer() != null
                            && a.getHimOfficer().getId().equals(userId);
                    default -> false;
                })
                .map(a -> {
                    var queue = "NURSE".equals(role)
                            ? WorkQueueService.Queue.NURSING : WorkQueueService.Queue.HIM;
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("appointmentPublicId", a.getPublicId());
                    row.put("reference", a.getReference());
                    row.put("appointmentDate", a.getAppointmentDate());
                    row.put("room", a.getRoom());
                    row.put("patientName", a.getPatient().getFirstName() + " "
                            + a.getPatient().getLastName());
                    row.put("ehrNumber", a.getPatient().getEhrNumber());
                    row.put("state", workQueueService.stateOf(a, queue).name());
                    row.put("startedAt", queue == WorkQueueService.Queue.NURSING
                            ? a.getNursingStartedAt() : a.getHimStartedAt());
                    row.put("completedAt", queue == WorkQueueService.Queue.NURSING
                            ? a.getNursingCompletedAt() : a.getHimCompletedAt());
                    row.put("exceptionReason", queue == WorkQueueService.Queue.NURSING
                            ? a.getNursingExceptionReason() : a.getHimExceptionReason());
                    // Promised by this endpoint's description and previously absent.
                    row.put("vitalsRecorded",
                            !vitalsService.forAppointment(a.getId()).isEmpty());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> prescriptionRow(Prescription p) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", p.getPublicId());
        row.put("issueNumber", p.getIssueNumber());
        row.put("status", p.getStatus().name());
        row.put("issueDate", p.getIssueDate());
        row.put("expiryDate", p.getExpiryDate());
        row.put("clinicalInformation", p.getClinicalInformation());
        row.put("items", p.getItems().stream().map(i -> Map.of(
                "medication", i.getMedication(),
                "strength", String.valueOf(i.getStrength()),
                "frequency", String.valueOf(i.getFrequency()),
                "duration", String.valueOf(i.getDuration()),
                "instructions", String.valueOf(i.getInstructions()))).toList());
        return row;
    }

    private Map<String, Object> investigationRow(Investigation i) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("publicId", i.getPublicId());
        row.put("issueNumber", i.getIssueNumber());
        row.put("status", i.getStatus().name());
        row.put("issueDate", i.getIssueDate());
        row.put("expiryDate", i.getExpiryDate());
        row.put("clinicalInformation", i.getClinicalInformation());
        row.put("panels", i.getItems().stream().map(p -> Map.of(
                "panelName", p.getPanelName(),
                "panelCode", String.valueOf(p.getPanelCode()))).toList());
        return row;
    }
}
