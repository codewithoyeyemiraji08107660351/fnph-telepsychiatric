package com.fnph.telepsychiatric.triage.api;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.triage.ConsentDocument;
import com.fnph.telepsychiatric.triage.ConsentDocumentRepository;
import com.fnph.telepsychiatric.triage.TriageQuestion;
import com.fnph.telepsychiatric.triage.TriageQuestionSet;
import com.fnph.telepsychiatric.triage.TriageQuestionSetRepository;
import com.fnph.telepsychiatric.triage.TriageService.TriageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writing and reviewing consent texts and triage question sets.
 *
 * Publishing already existed and correctly refuses the seeded PLACEHOLDER
 * wording, but nothing could list the versions or write a new one. Consent and
 * triage could therefore never be published through the API, and without a
 * published version no patient can book and no centre can refer.
 *
 * A published or retired version is never edited. Changing wording means a new
 * version, so every acceptance and every triage answer keeps pointing at the
 * exact text the person saw.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Consent and Triage Administration")
public class ConsentAdminController {

    private static final String AUDIENCES = "FNPH_PATIENT|CENTRE";

    private final ConsentDocumentRepository consentDocuments;
    private final TriageQuestionSetRepository questionSets;
    private final AuditService auditService;

    public record ConsentDraftRequest(
            @NotBlank @Pattern(regexp = AUDIENCES) String audience,
            @NotBlank @Size(max = 30) String version,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 50_000) String body) {
    }

    public record ConsentTextRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 50_000) String body) {
    }

    public record QuestionRequest(
            @NotBlank @Size(max = 1000) String questionText,
            @NotBlank @Pattern(regexp = "YES|NO") String stopAnswer,
            @NotBlank @Size(max = 200) String stopReason) {
    }

    public record QuestionSetDraftRequest(
            @NotBlank @Pattern(regexp = AUDIENCES) String audience,
            @NotBlank @Size(max = 30) String version,
            @NotEmpty @Size(max = 20) List<@Valid QuestionRequest> questions) {
    }

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------

    @GetMapping("/consent")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Transactional(readOnly = true)
    @Operation(summary = "Consent texts for an audience, newest first")
    public ResponseEntity<List<Map<String, Object>>> consentVersions(@RequestParam String audience) {
        return ResponseEntity.ok(consentDocuments.findAllByAudienceOrderByCreatedAtDesc(audience).stream()
                .map(ConsentAdminController::consentRow).toList());
    }

    @PostMapping("/consent")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Transactional
    @Operation(summary = "Write a new consent version as a draft")
    public ResponseEntity<Map<String, Object>> createConsent(@Valid @RequestBody ConsentDraftRequest request) {
        String version = request.version().trim();
        boolean taken = consentDocuments.findAllByAudienceOrderByCreatedAtDesc(request.audience()).stream()
                .anyMatch(d -> d.getVersion().equalsIgnoreCase(version));
        if (taken) {
            throw new TriageException("Version " + version + " already exists for this audience. Use a new version.");
        }
        ConsentDocument document = new ConsentDocument();
        document.setAudience(request.audience());
        document.setVersion(version);
        document.setTitle(request.title().trim());
        document.setBody(request.body().trim());
        document.setStatus("DRAFT");
        ConsentDocument saved = consentDocuments.save(document);
        audit("ConsentDocument", saved.getId(), "Consent draft %s written for %s".formatted(version, request.audience()));
        return ResponseEntity.status(HttpStatus.CREATED).body(consentRow(saved));
    }

    @PutMapping("/consent/{documentPublicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Transactional
    @Operation(summary = "Change the wording of a consent draft")
    public ResponseEntity<Map<String, Object>> editConsent(@PathVariable String documentPublicId,
                                                           @Valid @RequestBody ConsentTextRequest request) {
        ConsentDocument document = consentDocuments.findByPublicId(documentPublicId)
                .orElseThrow(() -> new TriageException("No such consent document"));
        if (!"DRAFT".equals(document.getStatus())) {
            throw new TriageException("Only a draft can be edited. Write a new version to change published wording.");
        }
        document.setTitle(request.title().trim());
        document.setBody(request.body().trim());
        audit("ConsentDocument", document.getId(), "Consent draft %s edited".formatted(document.getVersion()));
        return ResponseEntity.ok(consentRow(consentDocuments.save(document)));
    }

    // ------------------------------------------------------------------
    // Triage
    // ------------------------------------------------------------------

    @GetMapping("/triage")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Transactional(readOnly = true)
    @Operation(summary = "Triage question sets for an audience, newest first, with their questions")
    public ResponseEntity<List<Map<String, Object>>> questionSets(@RequestParam String audience) {
        return ResponseEntity.ok(questionSets.findAllByAudienceOrderByCreatedAtDesc(audience).stream()
                .map(ConsentAdminController::setRow).toList());
    }

    @PostMapping("/triage")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).CONSENT_MANAGE_VERSIONS)")
    @Transactional
    @Operation(summary = "Write a new triage question set as a draft",
            description = "stopAnswer is the answer that stops the booking and shows the emergency route.")
    public ResponseEntity<Map<String, Object>> createQuestionSet(@Valid @RequestBody QuestionSetDraftRequest request) {
        String version = request.version().trim();
        boolean taken = questionSets.findAllByAudienceOrderByCreatedAtDesc(request.audience()).stream()
                .anyMatch(s -> s.getVersion().equalsIgnoreCase(version));
        if (taken) {
            throw new TriageException("Version " + version + " already exists for this audience. Use a new version.");
        }
        TriageQuestionSet set = new TriageQuestionSet();
        set.setAudience(request.audience());
        set.setVersion(version);
        set.setStatus("DRAFT");
        int sequence = 1;
        for (QuestionRequest q : request.questions()) {
            TriageQuestion question = new TriageQuestion();
            question.setQuestionSet(set);
            question.setSequence(sequence++);
            question.setQuestionText(q.questionText().trim());
            question.setStopAnswer(q.stopAnswer());
            question.setStopReason(q.stopReason().trim());
            set.getQuestions().add(question);
        }
        TriageQuestionSet saved = questionSets.save(set);
        audit("TriageQuestionSet", saved.getId(), "Triage draft %s written for %s with %d questions"
                .formatted(version, request.audience(), request.questions().size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(setRow(saved));
    }

    private void audit(String entityType, Long id, String details) {
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType(entityType)
                .entityId(id)
                .details(details)
                .build());
    }

    private static Map<String, Object> consentRow(ConsentDocument d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicId", d.getPublicId());
        row.put("audience", d.getAudience());
        row.put("version", d.getVersion());
        row.put("title", d.getTitle());
        row.put("body", d.getBody());
        row.put("status", d.getStatus());
        row.put("placeholder", d.getBody() != null && d.getBody().contains("PLACEHOLDER"));
        row.put("effectiveFrom", d.getEffectiveFrom());
        row.put("retiredAt", d.getRetiredAt());
        row.put("publishedBy", d.getPublishedBy());
        row.put("createdAt", d.getCreatedAt());
        return row;
    }

    private static Map<String, Object> setRow(TriageQuestionSet s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicId", s.getPublicId());
        row.put("audience", s.getAudience());
        row.put("version", s.getVersion());
        row.put("status", s.getStatus());
        row.put("effectiveFrom", s.getEffectiveFrom());
        row.put("retiredAt", s.getRetiredAt());
        row.put("createdAt", s.getCreatedAt());
        row.put("placeholder", s.getQuestions().stream().anyMatch(q -> q.getQuestionText().contains("PLACEHOLDER")));
        row.put("questions", s.getQuestions().stream().map(q -> {
            Map<String, Object> qr = new LinkedHashMap<>();
            qr.put("sequence", q.getSequence());
            qr.put("questionText", q.getQuestionText());
            qr.put("stopAnswer", q.getStopAnswer());
            qr.put("stopReason", q.getStopReason());
            return qr;
        }).toList());
        return row;
    }
}
