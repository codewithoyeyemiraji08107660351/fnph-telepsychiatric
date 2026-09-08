package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Issuing, downloading and verifying clinical documents.
 *
 * <h2>Download counting is atomic</h2>
 *
 * The check and the increment happen in one statement. A patient on a poor
 * connection retrying a download must not burn their single allowance twice,
 * and two devices requesting at once must not both succeed against a limit of
 * one. Checking then updating in two steps loses both of those races.
 *
 * <h2>Verification returns almost nothing</h2>
 *
 * A pharmacist scanning a QR code needs to know the document is genuine and
 * still valid. They do not need the patient's name, and the person scanning
 * might be anyone who found a piece of paper. So the public response carries
 * the issue number, the dates and a status, and nothing else.
 *
 * <h2>A saved copy verifies as expired</h2>
 *
 * The QR encodes a token that resolves here. A self-contained payload would
 * keep saying "valid" forever on a photograph taken the day it was issued.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IssuedDocumentService {

    private static final DateTimeFormatter ISSUE_DATE = DateTimeFormatter.ofPattern("yyMM");

    private final IssuedDocumentRepository documentRepository;
    private final DocumentVerificationRepository verificationRepository;
    private final DocumentDownloadEventRepository downloadRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final ConfigurationService configuration;
    private final AuditService auditService;

    @Value("${application.base-url}")
    private String baseUrl;

    // -----------------------------------------------------------------
    // Issue
    // -----------------------------------------------------------------

    @Transactional
    public IssuedDocument issue(DocumentType type, Long sourceId, Patient patient) {
        return documentRepository.findByDocumentTypeAndSourceId(type, sourceId)
                .orElseGet(() -> create(type, sourceId, patient));
    }

    private IssuedDocument create(DocumentType type, Long sourceId, Patient patient) {
        LocalDateTime now = LocalDateTime.now();

        int validityDays = switch (type) {
            case PRESCRIPTION -> configuration.getInt(ConfigurationKeys.PRESCRIPTION_VALIDITY_DAYS);
            case INVESTIGATION_REQUEST ->
                    configuration.getInt(ConfigurationKeys.INVESTIGATION_VALIDITY_DAYS);
            default -> 30;
        };

        // Frozen at issue. A patient's allowance must not change under them
        // because governance adjusted a setting afterwards.
        int maxDownloads = switch (type) {
            case PRESCRIPTION -> configuration.getInt(ConfigurationKeys.PRESCRIPTION_MAX_DOWNLOADS);
            case INVESTIGATION_REQUEST ->
                    configuration.getInt(ConfigurationKeys.INVESTIGATION_MAX_DOWNLOADS);
            default -> 3;
        };

        IssuedDocument document = new IssuedDocument();
        document.setDocumentType(type);
        document.setSourceId(sourceId);
        document.setIssueNumber(nextIssueNumber(type, now));
        document.setPatient(patient);
        document.setIssuedAt(now);
        document.setExpiresAt(now.plusDays(validityDays));
        document.setMaxDownloads(maxDownloads);
        document.setStatus(DocumentStatus.ACTIVE);
        IssuedDocument saved = documentRepository.save(document);

        String rawToken = Tokens.generate();
        DocumentVerification verification = new DocumentVerification();
        verification.setIssuedDocument(saved);
        verification.setDocumentType(type.name());
        verification.setDocumentId(sourceId);
        verification.setIssueNumber(saved.getIssueNumber());
        verification.setVerificationToken(Tokens.hash(rawToken));
        verification.setVerificationUrl(baseUrl + "/verify/" + rawToken);
        verificationRepository.save(verification);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("IssuedDocument")
                .entityId(saved.getId())
                .details("%s issued as %s, valid %d days, %d download(s)"
                        .formatted(type, saved.getIssueNumber(), validityDays, maxDownloads))
                .build());

        log.info("Issued {} {} valid until {}", type, saved.getIssueNumber(), saved.getExpiresAt());
        return saved;
    }

    /**
     * Issue numbers are read aloud on the phone, so the format avoids anything
     * ambiguous when spoken: a type prefix, the year and month, and a random
     * suffix from an alphabet with no I, L, O, U, 0 or 1.
     */
    private String nextIssueNumber(DocumentType type, LocalDateTime now) {
        String prefix = switch (type) {
            case PRESCRIPTION -> "RX";
            case INVESTIGATION_REQUEST -> "IV";
            case CLINICAL_SUMMARY -> "CS";
            case FOLLOW_UP_RECOMMENDATION -> "FU";
        };
        return "%s-%s-%s".formatted(prefix, now.format(ISSUE_DATE),
                Tokens.generateRecoveryCode().replace("-", "").substring(0, 8));
    }

    // -----------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------

    /**
     * Claims one download.
     *
     * @throws DocumentException when the allowance is used, the document has
     *         expired, or it was revoked. The document remains readable on
     *         screen after the allowance is used; only the file is refused.
     */
    @Transactional
    public IssuedDocument claimDownload(String documentPublicId, String ipAddress, String userAgent) {
        IssuedDocument document = documentRepository.findByPublicId(documentPublicId)
                .orElseThrow(() -> new DocumentException("No such document"));

        assertOwnedByCaller(document);

        LocalDateTime now = LocalDateTime.now();
        DocumentStatus effective = document.effectiveStatus(now);

        if (effective == DocumentStatus.REVOKED) {
            record(document, DownloadOutcome.REFUSED_REVOKED, ipAddress, userAgent);
            throw new DocumentException(
                    "That document has been withdrawn. Contact the hospital.");
        }
        if (effective == DocumentStatus.EXPIRED) {
            record(document, DownloadOutcome.REFUSED_EXPIRED, ipAddress, userAgent);
            throw new DocumentException(
                    "That document expired on " + document.getExpiresAt().toLocalDate()
                            + ". Contact the hospital if you still need it.");
        }

        // One statement. Two devices at once cannot both succeed against a
        // limit of one, and a retry cannot burn the allowance twice.
        int claimed = documentRepository.claimDownload(document.getId(), now);

        if (claimed == 0) {
            record(document, DownloadOutcome.REFUSED_LIMIT_REACHED, ipAddress, userAgent);
            throw new DocumentException(
                    "You have already downloaded this document. You can still read it on "
                            + "screen until it expires on " + document.getExpiresAt().toLocalDate()
                            + ".");
        }

        record(document, DownloadOutcome.ALLOWED, ipAddress, userAgent);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.DOCUMENT_DOWNLOADED)
                .entityType("IssuedDocument")
                .entityId(document.getId())
                .details(document.getIssueNumber())
                .ipAddress(ipAddress)
                .build());

        return documentRepository.findById(document.getId()).orElseThrow();
    }

    // -----------------------------------------------------------------
    // Verify
    // -----------------------------------------------------------------

    /**
     * The public check behind a QR code.
     *
     * Deliberately thin. It confirms the document was issued here and says
     * whether it is still valid. It never returns a patient name, a clinician
     * name, a medication or a diagnosis, because the person scanning could be
     * anyone who found a piece of paper, and the document in their hand already
     * carries what they legitimately need.
     */
    @Transactional
    public VerificationResult verify(String rawToken, String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now();

        VerificationOutcome outcome;
        IssuedDocument document = null;

        var found = verificationRepository.findByVerificationToken(Tokens.hash(rawToken));
        if (found.isEmpty() || found.get().getIssuedDocument() == null) {
            outcome = VerificationOutcome.NOT_FOUND;
        } else {
            document = found.get().getIssuedDocument();
            DocumentVerification verification = found.get();
            verification.setLastVerifiedAt(now);
            verification.setLastVerifiedIp(ipAddress);
            verification.setVerificationCount(verification.getVerificationCount() + 1);
            verificationRepository.save(verification);

            outcome = switch (document.effectiveStatus(now)) {
                case ACTIVE -> VerificationOutcome.VALID;
                case EXPIRED -> VerificationOutcome.EXPIRED;
                case REVOKED -> VerificationOutcome.REVOKED;
                case SUPERSEDED -> VerificationOutcome.SUPERSEDED;
            };
        }

        VerificationAttempt attempt = new VerificationAttempt();
        attempt.setTokenPresented(Tokens.hash(rawToken));
        attempt.setOutcome(outcome);
        attempt.setIpAddress(ipAddress);
        attempt.setUserAgent(userAgent);
        attempt.setAttemptedAt(now);
        attemptRepository.save(attempt);

        if (document == null) {
            return new VerificationResult(outcome.name(), null, null, null, null);
        }
        return new VerificationResult(
                outcome.name(),
                document.getIssueNumber(),
                document.getDocumentType().name(),
                document.getIssuedAt().toLocalDate().toString(),
                document.getExpiresAt().toLocalDate().toString());
    }

    // -----------------------------------------------------------------

    @Transactional
    public IssuedDocument revoke(String documentPublicId, String reason) {
        IssuedDocument document = documentRepository.findByPublicId(documentPublicId)
                .orElseThrow(() -> new DocumentException("No such document"));

        document.setStatus(DocumentStatus.REVOKED);
        document.setRevokedAt(LocalDateTime.now());
        document.setRevokedBy(CurrentUser.usernameOrSystem());
        document.setRevokedReason(reason);
        documentRepository.save(document);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.DOCUMENT_REVOKED)
                .entityType("IssuedDocument")
                .entityId(document.getId())
                .details(document.getIssueNumber())
                .reason(reason)
                .build());

        // A saved copy still verifies, and now verifies as revoked. That is
        // the point: a patient holding a printed page finds out it is no
        // longer valid rather than presenting it at a pharmacy.
        return document;
    }

    @Transactional(readOnly = true)
    public List<IssuedDocument> forPatient(Long patientId) {
        return documentRepository.findAllByPatientIdOrderByIssuedAtDesc(patientId);
    }

    @Transactional(readOnly = true)
    public String verificationUrlFor(Long documentId) {
        return verificationRepository.findByIssuedDocumentId(documentId)
                .map(DocumentVerification::getVerificationUrl)
                .orElseThrow(() -> new DocumentException("No verification record"));
    }

    /** Marks lapsed documents expired. Run nightly. */
    @Transactional
    public int expireLapsed() {
        return documentRepository.expireLapsed(LocalDateTime.now());
    }

    private void assertOwnedByCaller(IssuedDocument document) {
        CurrentUser.get().ifPresent(principal -> {
            if (principal.getPatientId() != null && document.getPatient() != null
                    && !principal.getPatientId().equals(document.getPatient().getId())) {
                // Same message as "no such document" would give. Confirming it
                // exists would let a patient probe for other people's records.
                throw new DocumentException("No such document");
            }
        });
    }

    private void record(IssuedDocument document, DownloadOutcome outcome,
                        String ipAddress, String userAgent) {
        DocumentDownloadEvent event = new DocumentDownloadEvent();
        event.setIssuedDocumentId(document.getId());
        event.setDownloadedBy(CurrentUser.usernameOrSystem());
        event.setDownloadedAt(LocalDateTime.now());
        event.setOutcome(outcome);
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);
        downloadRepository.save(event);
    }

    /** What the public verification endpoint returns, and no more. */
    public record VerificationResult(String status, String issueNumber, String documentType,
                                     String issuedOn, String expiresOn) {
    }

    public static class DocumentException extends RuntimeException {
        public DocumentException(String message) {
            super(message);
        }
    }
}
