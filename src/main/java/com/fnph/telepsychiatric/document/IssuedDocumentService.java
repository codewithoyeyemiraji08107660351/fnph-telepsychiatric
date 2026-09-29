package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.storage.StorageArea;
import com.fnph.telepsychiatric.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
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
 * and two devices requesting at once must not both succeed against a limit of one.
 *
 * <h2>Verification returns almost nothing</h2>
 *
 * A pharmacist scanning a QR code needs to know the document is genuine and
 * still valid. They do not need the patient's name, and the person scanning
 * might be anyone who found a piece of paper.
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

    private static final DateTimeFormatter ISSUE_DATE =
            DateTimeFormatter.ofPattern("yyMM");

    private final IssuedDocumentRepository documentRepository;
    private final DocumentVerificationRepository verificationRepository;
    private final DocumentDownloadEventRepository downloadRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final com.fnph.telepsychiatric.storage.StorageService storageService;
    private final ConfigurationService configuration;
    private final AuditService auditService;

    // Links a person opens in a browser point at the portal, not the API host.
    @Value("${application.portal-url:${application.base-url}}")
    private String baseUrl;

    // -----------------------------------------------------------------
    // Issue
    // -----------------------------------------------------------------

    @Transactional
    public IssuedDocument issue(
            DocumentType type,
            Long sourceId,
            Patient patient) {

        return documentRepository
                .findByDocumentTypeAndSourceId(type, sourceId)
                .orElseGet(() -> create(type, sourceId, patient));
    }

    /**
     * Attaches the rendered file to an issued document.
     *
     * Rendered once, at issue, not on every download.
     */
    @Transactional
    public IssuedDocument attachRendered(
            Long documentId,
            byte[] pdf,
            String contentType) {

        IssuedDocument document = documentRepository
                .findById(documentId)
                .orElseThrow(() ->
                        new DocumentException("No such document"));

        StoredObject stored = storageService.store(
                StorageArea.DOCUMENTS,
                new java.io.ByteArrayInputStream(pdf),
                document.getIssueNumber() + ".pdf",
                contentType
        );

        document.setStorageArea(stored.area());
        document.setStoragePath(stored.path());
        document.setFileChecksum(stored.checksum());
        document.setFileSizeBytes(stored.sizeBytes());
        document.setContentType(contentType);

        return documentRepository.save(document);
    }

    // -----------------------------------------------------------------
    // Rendered file
    // -----------------------------------------------------------------

    /**
     * Opens the rendered file and verifies it through the storage service.
     *
     * This method does NOT consume the patient's download allowance.
     */
    @Transactional(readOnly = true)
    public RenderedFile openRendered(IssuedDocument document) {

        if (document.getStoragePath() == null) {
            throw new DocumentException(
                    "That document has not been rendered yet. Contact the helpdesk."
            );
        }

        return new RenderedFile(
                storageService.read(
                        document.getStorageArea(),
                        document.getStoragePath(),
                        document.getFileChecksum()
                ),
                document.getIssueNumber() + ".pdf",
                document.getContentType() == null
                        ? "application/pdf"
                        : document.getContentType(),
                document.getFileSizeBytes() == null
                        ? 0
                        : document.getFileSizeBytes()
        );
    }

    public record RenderedFile(
            InputStream stream,
            String filename,
            String contentType,
            long sizeBytes
    ) {
    }

    // -----------------------------------------------------------------
    // Issue creation
    // -----------------------------------------------------------------

    private IssuedDocument create(
            DocumentType type,
            Long sourceId,
            Patient patient) {

        LocalDateTime now = LocalDateTime.now();

        int validityDays = switch (type) {
            case PRESCRIPTION ->
                    configuration.getInt(
                            ConfigurationKeys.PRESCRIPTION_VALIDITY_DAYS
                    );

            case INVESTIGATION_REQUEST ->
                    configuration.getInt(
                            ConfigurationKeys.INVESTIGATION_VALIDITY_DAYS
                    );

            default -> 30;
        };

        // Frozen at issue.
        int maxDownloads = switch (type) {
            case PRESCRIPTION ->
                    configuration.getInt(
                            ConfigurationKeys.PRESCRIPTION_MAX_DOWNLOADS
                    );

            case INVESTIGATION_REQUEST ->
                    configuration.getInt(
                            ConfigurationKeys.INVESTIGATION_MAX_DOWNLOADS
                    );

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

        IssuedDocument saved =
                documentRepository.save(document);

        String rawToken = Tokens.generate();

        DocumentVerification verification =
                new DocumentVerification();

        verification.setIssuedDocument(saved);
        verification.setDocumentType(type.name());
        verification.setDocumentId(sourceId);
        verification.setIssueNumber(saved.getIssueNumber());
        verification.setVerificationToken(Tokens.hash(rawToken));
        verification.setVerificationUrl(
                baseUrl + "/verify/" + rawToken
        );

        verificationRepository.save(verification);

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(AuditAction.RECORD_CREATED)
                        .entityType("IssuedDocument")
                        .entityId(saved.getId())
                        .details(
                                "%s issued as %s, valid %d days, %d download(s)"
                                        .formatted(
                                                type,
                                                saved.getIssueNumber(),
                                                validityDays,
                                                maxDownloads
                                        )
                        )
                        .build()
        );

        log.info(
                "Issued {} {} valid until {}",
                type,
                saved.getIssueNumber(),
                saved.getExpiresAt()
        );

        return saved;
    }

    /**
     * Issue numbers are read aloud on the phone, so the format avoids
     * ambiguous characters.
     */
    private String nextIssueNumber(
            DocumentType type,
            LocalDateTime now) {

        String prefix = switch (type) {
            case PRESCRIPTION -> "RX";
            case INVESTIGATION_REQUEST -> "IV";
            case CLINICAL_SUMMARY -> "CS";
            case FOLLOW_UP_RECOMMENDATION -> "FU";
        };

        return "%s-%s-%s".formatted(
                prefix,
                now.format(ISSUE_DATE),
                Tokens.generateRecoveryCode()
                        .replace("-", "")
                        .substring(0, 8)
        );
    }

    // -----------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------

    /**
     * Finds and validates a document for download WITHOUT consuming
     * the download allowance.
     *
     * This is deliberately separate from claimDownload().
     */
    @Transactional(readOnly = true)
    public IssuedDocument findForDownload(
            String documentPublicId) {

        IssuedDocument document =
                documentRepository
                        .findByPublicId(documentPublicId)
                        .orElseThrow(() ->
                                new DocumentException(
                                        "No such document"
                                )
                        );

        assertOwnedByCaller(document);

        LocalDateTime now = LocalDateTime.now();

        DocumentStatus effective =
                document.effectiveStatus(now);

        if (effective == DocumentStatus.REVOKED) {
            throw new DocumentException(
                    "This document has been revoked."
            );
        }

        if (effective == DocumentStatus.EXPIRED) {
            throw new DocumentException(
                    "This document has expired."
            );
        }

        return document;
    }

    /**
     * Claims one download atomically.
     *
     * The repository performs the actual check/increment atomically so
     * concurrent requests cannot both consume the same allowance.
     */
    @Transactional
    public IssuedDocument claimDownload(
            String documentPublicId,
            String ipAddress,
            String userAgent) {

        IssuedDocument document =
                documentRepository
                        .findByPublicId(documentPublicId)
                        .orElseThrow(() ->
                                new DocumentException(
                                        "No such document"
                                )
                        );

        assertOwnedByCaller(document);

        LocalDateTime now = LocalDateTime.now();

        DocumentStatus effective =
                document.effectiveStatus(now);

        if (effective == DocumentStatus.REVOKED) {
            throw new DocumentException(
                    "This document has been revoked."
            );
        }

        if (effective == DocumentStatus.EXPIRED) {
            throw new DocumentException(
                    "This document has expired."
            );
        }

        int claimed =
                documentRepository.claimDownload(
                        document.getId(),
                        now
                );

        if (claimed == 0) {
            throw new DocumentException(
                    "You have already downloaded this document."
            );
        }

        record(
                document,
                DownloadOutcome.ALLOWED,
                ipAddress,
                userAgent
        );

        auditService.record(
        AuditService.AuditEvent.builder()
                .action(AuditAction.DOCUMENT_DOWNLOADED)
                .entityType("IssuedDocument")
                .entityId(document.getId())
                .details("Document downloaded: " + document.getPublicId())
                .outcome("SUCCESS")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build()
);
 

        return document;
    }

    // -----------------------------------------------------------------
    // Verify
    // -----------------------------------------------------------------

    /**
     * The public check behind a QR code.
     */
    @Transactional
    public VerificationResult verify(
            String rawToken,
            String ipAddress,
            String userAgent) {

        LocalDateTime now = LocalDateTime.now();

        VerificationOutcome outcome;
        IssuedDocument document = null;

        var found =
                verificationRepository.findByVerificationToken(
                        Tokens.hash(rawToken)
                );

        if (found.isEmpty()
                || found.get().getIssuedDocument() == null) {

            outcome = VerificationOutcome.NOT_FOUND;

        } else {

            document =
                    found.get().getIssuedDocument();

            DocumentVerification verification =
                    found.get();

            verification.setLastVerifiedAt(now);
            verification.setLastVerifiedIp(ipAddress);
            verification.setVerificationCount(
                    verification.getVerificationCount() + 1
            );

            verificationRepository.save(verification);

            outcome =
                    switch (document.effectiveStatus(now)) {
                        case ACTIVE ->
                                VerificationOutcome.VALID;

                        case EXPIRED ->
                                VerificationOutcome.EXPIRED;

                        case REVOKED ->
                                VerificationOutcome.REVOKED;

                        case SUPERSEDED ->
                                VerificationOutcome.SUPERSEDED;
                    };
        }

        VerificationAttempt attempt =
                new VerificationAttempt();

        attempt.setTokenPresented(
                Tokens.hash(rawToken)
        );

        attempt.setOutcome(outcome);
        attempt.setIpAddress(ipAddress);
        attempt.setUserAgent(userAgent);
        attempt.setAttemptedAt(now);

        attemptRepository.save(attempt);

        if (document == null) {
            return new VerificationResult(
                    outcome.name(),
                    null,
                    null,
                    null,
                    null
            );
        }

        return new VerificationResult(
                outcome.name(),
                document.getIssueNumber(),
                document.getDocumentType().name(),
                document.getIssuedAt()
                        .toLocalDate()
                        .toString(),
                document.getExpiresAt()
                        .toLocalDate()
                        .toString()
        );
    }

    // -----------------------------------------------------------------
    // Revoke
    // -----------------------------------------------------------------

    @Transactional
    public IssuedDocument revoke(
            String documentPublicId,
            String reason) {

        IssuedDocument document =
                documentRepository
                        .findByPublicId(documentPublicId)
                        .orElseThrow(() ->
                                new DocumentException(
                                        "No such document"
                                )
                        );

        document.setStatus(DocumentStatus.REVOKED);
        document.setRevokedAt(LocalDateTime.now());
        document.setRevokedBy(
                CurrentUser.usernameOrSystem()
        );
        document.setRevokedReason(reason);

        documentRepository.save(document);

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(AuditAction.DOCUMENT_REVOKED)
                        .entityType("IssuedDocument")
                        .entityId(document.getId())
                        .details(document.getIssueNumber())
                        .reason(reason)
                        .build()
        );

        return document;
    }

    /**
     * Withdraws the document rendered from a clinical record.
     */
    @Transactional
    public void revokeForSource(
            DocumentType type,
            Long sourceId,
            String reason) {

        documentRepository
                .findByDocumentTypeAndSourceId(
                        type,
                        sourceId
                )
                .ifPresent(document ->
                        revoke(
                                document.getPublicId(),
                                reason
                        )
                );
    }

    // -----------------------------------------------------------------
    // Patient documents
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<IssuedDocument> forPatient(
            Long patientId) {

        return documentRepository
                .findAllByPatientIdOrderByIssuedAtDesc(
                        patientId
                );
    }

    @Transactional(readOnly = true)
    public String verificationUrlFor(
            Long documentId) {

        return verificationRepository
                .findByIssuedDocumentId(documentId)
                .map(DocumentVerification::getVerificationUrl)
                .orElseThrow(() ->
                        new DocumentException(
                                "No verification record"
                        )
                );
    }

    // -----------------------------------------------------------------
    // Expiry
    // -----------------------------------------------------------------

    /**
     * Marks lapsed documents expired. Run nightly.
     */
    @Transactional
    public int expireLapsed() {
        return documentRepository.expireLapsed(
                LocalDateTime.now()
        );
    }

    // -----------------------------------------------------------------
    // Authorization
    // -----------------------------------------------------------------

    private void assertOwnedByCaller(
            IssuedDocument document) {

        CurrentUser.get().ifPresent(principal -> {

            if (principal.getPatientId() != null) {

                if (document.getPatient() == null
                        || !principal.getPatientId()
                        .equals(document.getPatient().getId())) {

                    throw new DocumentException(
                            "No such document"
                    );
                }

                return;
            }

            if (principal.getCentreId() != null) {

                if (document.getCentre() == null
                        || !principal.getCentreId()
                        .equals(document.getCentre().getId())) {

                    throw new DocumentException(
                            "No such document"
                    );
                }
            }
        });
    }

    // -----------------------------------------------------------------
    // Download audit
    // -----------------------------------------------------------------

    private void record(
            IssuedDocument document,
            DownloadOutcome outcome,
            String ipAddress,
            String userAgent) {

        DocumentDownloadEvent event =
                new DocumentDownloadEvent();

        event.setIssuedDocumentId(
                document.getId()
        );

        event.setDownloadedBy(
                CurrentUser.usernameOrSystem()
        );

        event.setDownloadedAt(
                LocalDateTime.now()
        );

        event.setOutcome(outcome);
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);

        downloadRepository.save(event);
    }

    // -----------------------------------------------------------------
    // DTOs / exceptions
    // -----------------------------------------------------------------

    public record VerificationResult(
            String status,
            String issueNumber,
            String documentType,
            String issuedOn,
            String expiresOn) {
    }

    public static class DocumentException
            extends RuntimeException {

        public DocumentException(String message) {
            super(message);
        }
    }
}