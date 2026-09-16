package com.fnph.telepsychiatric.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Triage and consent.
 *
 * <h2>Triage exists to stop the journey, not to record it</h2>
 *
 * This service excludes emergencies, severe agitation, acute psychosis and
 * immediate risk. Triage finds those before a patient pays for a consultation
 * that cannot help them, which is the whole point of asking before the payment
 * step rather than after.
 *
 * A stopped triage is a redirection. The escalation text is shown and stored,
 * so a later reader knows exactly what the patient was told rather than only
 * that they were turned away.
 *
 * <h2>Nothing runs against a draft</h2>
 *
 * The seeded questions and consent text are placeholders and left in DRAFT.
 * Triage and consent both refuse outright with no published version, which is a
 * loud failure at the right moment. The alternative would be a patient
 * consenting to placeholder text that says "FNPH to supply".
 *
 * <h2>The stop answer comes from the question</h2>
 *
 * Not assumed to be yes. "Can the patient take part in a video conversation"
 * stops on no. Hard-coding yes would let a correctly worded question pass
 * exactly the patients it was written to catch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TriageService {

    private final TriageQuestionSetRepository questionSets;
    private final TriageResponseRepository responses;
    private final ConsentDocumentRepository consentDocuments;
    private final ConsentAcceptanceRepository consentAcceptances;
    private final ConfigurationService configuration;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // -----------------------------------------------------------------
    // Triage
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public TriageQuestionSet activeQuestions(String audience) {
        return questionSets
                .findFirstByAudienceAndStatusOrderByEffectiveFromDesc(audience, "PUBLISHED")
                .orElseThrow(() -> new TriageException(
                        "No published triage questions for " + audience + ". The seeded set is "
                                + "a placeholder and is deliberately left as a draft: FNPH must "
                                + "supply the approved wording and publish it before any patient "
                                + "can be triaged."));
    }

    /**
     * Evaluates a submission.
     *
     * Stops on the first question whose stop answer was given, and reports the
     * reason rather than a generic refusal, so the patient knows why they are
     * being redirected.
     */
    @Transactional
    public TriageResponse submit(Patient patient, String audience, Map<String, String> answers,
                                 String ipAddress) {
        TriageQuestionSet set = activeQuestions(audience);

        TriageQuestion stoppedOn = null;
        for (TriageQuestion question : set.getQuestions()) {
            String given = answers.get(question.getPublicId());
            if (given == null) {
                throw new TriageException(
                        "Every question has to be answered. Question " + question.getSequence()
                                + " was not.");
            }
            if (question.getStopAnswer().equalsIgnoreCase(given.trim())) {
                stoppedOn = question;
                break;
            }
        }

        TriageResponse response = new TriageResponse();
        response.setQuestionSet(set);
        response.setTriageVersion(set.getVersion());
        response.setPatient(patient);
        response.setSubmittedAt(LocalDateTime.now());
        response.setIpAddress(ipAddress);

        try {
            response.setAnswersJson(objectMapper.writeValueAsString(answers));
        } catch (Exception e) {
            response.setAnswersJson("{}");
        }

        if (stoppedOn == null) {
            response.setOutcome("PROCEED");
        } else {
            response.setOutcome("STOPPED");
            response.setStoppedOnQuestionId(stoppedOn.getId());
            response.setStopReason(stoppedOn.getStopReason());
            response.setEscalationShown(escalationText(stoppedOn.getStopReason()));

            // Logged at warn because a run of stops on the same question is
            // either a wording problem or a genuine cohort the service should
            // not be seeing, and both are worth noticing.
            log.warn("Triage stopped for patient {} on question {}: {}",
                    patient == null ? "unknown" : patient.getPublicId(),
                    stoppedOn.getSequence(), stoppedOn.getStopReason());
        }

        TriageResponse saved = responses.save(response);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("TriageResponse")
                .entityId(saved.getId())
                .details("Triage " + saved.getOutcome()
                        + (stoppedOn == null ? "" : ": " + stoppedOn.getStopReason()))
                .build());

        return saved;
    }

    /**
     * What a stopped patient is told.
     *
     * The emergency number comes from configuration, so FNPH can change it
     * without a release. Somebody being redirected because they are at
     * immediate risk should not be reading a number that was correct in 2026.
     */
    private String escalationText(String stopReason) {
        return """
               This service cannot help with what you have described, and it is not for \
               emergencies.

               Reason: %s

               Please go to the nearest emergency department now, or call %s. If you are at \
               the Federal Neuropsychiatric Hospital or one of the Centres of Excellence, \
               tell a member of staff straight away.

               Nothing has been charged and no appointment has been made."""
                .formatted(stopReason,
                        configuration.getString(ConfigurationKeys.CLINICAL_EMERGENCY_NUMBER));
    }

    /**
     * Whether this patient has a current triage allowing them to book.
     *
     * Checked at booking. A STOPPED triage does not clear by resubmitting a
     * different answer set on its own; the most recent submission is what
     * counts, which is deliberate: a patient whose situation genuinely changed
     * should be able to proceed, and the earlier answer stays in the record.
     */
    @Transactional(readOnly = true)
    public boolean mayProceed(Long patientId) {
        return responses.findAllByPatientIdOrderBySubmittedAtDesc(patientId).stream()
                .findFirst()
                .map(r -> "PROCEED".equals(r.getOutcome()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<TriageResponse> historyFor(Long patientId) {
        return responses.findAllByPatientIdOrderBySubmittedAtDesc(patientId);
    }

    // -----------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public ConsentDocument activeConsent(String audience) {
        return consentDocuments
                .findFirstByAudienceAndStatusOrderByEffectiveFromDesc(audience, "PUBLISHED")
                .orElseThrow(() -> new TriageException(
                        "No published consent document for " + audience + ". The seeded text is "
                                + "a placeholder and is deliberately left as a draft: a patient "
                                + "must not consent to text that says \"FNPH to supply\"."));
    }

    @Transactional
    public ConsentAcceptance accept(Patient patient, String audience, String acceptedBy,
                                    String witnessedBy, String ipAddress, String userAgent) {
        ConsentDocument document = activeConsent(audience);

        ConsentAcceptance acceptance = new ConsentAcceptance();
        acceptance.setConsentDocument(document);
        // Recorded on the row as well as referenced, so it survives the
        // document being retired.
        acceptance.setConsentVersion(document.getVersion());
        acceptance.setPatient(patient);
        acceptance.setAcceptedAt(LocalDateTime.now());
        acceptance.setAcceptedBy(acceptedBy);
        acceptance.setWitnessedBy(witnessedBy);
        acceptance.setIpAddress(ipAddress);
        acceptance.setUserAgent(userAgent);
        ConsentAcceptance saved = consentAcceptances.save(acceptance);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("ConsentAcceptance")
                .entityId(saved.getId())
                .details("Consent version " + document.getVersion() + " accepted")
                .build());

        return saved;
    }

    /** The consent a booking can be made against. */
    @Transactional(readOnly = true)
    public Optional<ConsentAcceptance> currentConsent(Long patientId) {
        return consentAcceptances.findFirstByPatientIdOrderByAcceptedAtDesc(patientId);
    }

    /**
     * Publishes a version, retiring the one it replaces.
     *
     * Retired rather than deleted. Every acceptance against it still has to be
     * readable, because that is what those patients actually agreed to.
     */
    @Transactional
    public ConsentDocument publishConsent(String documentPublicId) {
        ConsentDocument document = consentDocuments.findByPublicId(documentPublicId)
                .orElseThrow(() -> new TriageException("No such consent document"));

        if ("PUBLISHED".equals(document.getStatus())) {
            return document;
        }
        if (document.getBody().contains("PLACEHOLDER")) {
            throw new TriageException(
                    "That text still contains PLACEHOLDER. Replace it with the approved "
                            + "wording before publishing: a patient must not consent to a note "
                            + "asking FNPH to supply the terms.");
        }

        consentDocuments.findFirstByAudienceAndStatusOrderByEffectiveFromDesc(
                document.getAudience(), "PUBLISHED").ifPresent(previous -> {
            previous.setStatus("RETIRED");
            previous.setRetiredAt(LocalDateTime.now());
            consentDocuments.save(previous);
        });

        document.setStatus("PUBLISHED");
        document.setEffectiveFrom(LocalDateTime.now());
        document.setPublishedBy(CurrentUser.usernameOrSystem());
        return consentDocuments.save(document);
    }

    @Transactional
    public TriageQuestionSet publishQuestions(String setPublicId) {
        TriageQuestionSet set = questionSets.findByPublicId(setPublicId)
                .orElseThrow(() -> new TriageException("No such question set"));

        if (set.getQuestions().stream()
                .anyMatch(q -> q.getQuestionText().contains("PLACEHOLDER"))) {
            throw new TriageException(
                    "Those questions still contain PLACEHOLDER text. The wording decides which "
                            + "patients are turned away from a psychiatric service, so it has to "
                            + "be FNPH's own.");
        }

        questionSets.findFirstByAudienceAndStatusOrderByEffectiveFromDesc(
                set.getAudience(), "PUBLISHED").ifPresent(previous -> {
            previous.setStatus("RETIRED");
            previous.setRetiredAt(LocalDateTime.now());
            questionSets.save(previous);
        });

        set.setStatus("PUBLISHED");
        set.setEffectiveFrom(LocalDateTime.now());
        return questionSets.save(set);
    }

    /**
     * Refuses a booking unless the patient has accepted the current consent and
     * the most recent triage, on the current question set, said PROCEED.
     *
     * The stop screen in the patient app was the only thing enforcing this, so
     * anyone calling the booking API directly skipped the safety questions.
     * The most recent response decides, not the most recent PROCEED: a patient
     * who passed last month and stopped today must not book on the old answer.
     *
     * How long a PROCEED stays valid is not decided here. That is an FNPH
     * clinical governance question; until it is answered, a new question set
     * version is what forces a fresh triage.
     */
    @Transactional(readOnly = true)
    public void requireClearedForBooking(Long patientId, String audience) {
        String consentVersion = activeConsent(audience).getVersion();
        boolean consented = consentAcceptances.findFirstByPatientIdOrderByAcceptedAtDesc(patientId)
                .map(a -> consentVersion.equals(a.getConsentVersion()))
                .orElse(false);
        if (!consented) {
            throw new TriageException("Accept the current consent before choosing a time.");
        }

        String questionVersion = activeQuestions(audience).getVersion();
        TriageResponse latest = responses.findAllByPatientIdOrderBySubmittedAtDesc(patientId)
                .stream().findFirst()
                .orElseThrow(() -> new TriageException(
                        "Answer the safety questions before choosing a time."));
        if (!questionVersion.equals(latest.getTriageVersion())) {
            throw new TriageException(
                    "The safety questions have changed. Answer them again before choosing a time.");
        }
        if (!"PROCEED".equals(latest.getOutcome())) {
            throw new TriageException(
                    "Your answers mean a video appointment is not right for you now. "
                            + "Please use the emergency contact shown to you.");
        }
    }

    public static class TriageException extends RuntimeException {
        public TriageException(String message) {
            super(message);
        }
    }
}
