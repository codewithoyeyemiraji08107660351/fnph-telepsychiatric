package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The stored qrCode column was removed. Holding a base64 image per document is
 * dead weight; the image is generated on demand from the token.
 *
 * The QR points at a minimal verification result and never carries clinical
 * content, so an unauthenticated scan can confirm validity without disclosing
 * anything about the patient.
 */
@Entity
@Table(name = "document_verifications", uniqueConstraints = {
        @UniqueConstraint(name = "uk_doc_verifications_token", columnNames = "verification_token")
}, indexes = {
        @Index(name = "idx_doc_verifications_issue_number", columnList = "issue_number")
})
@Getter
@Setter
public class DocumentVerification extends BaseEntity {

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_document_id")
    private IssuedDocument issuedDocument;

    @Column(name = "issue_number", nullable = false, length = 50)
    private String issueNumber;

    @Column(name = "verification_token", nullable = false, length = 64)
    private String verificationToken;

    @Column(name = "verification_url", nullable = false, length = 500)
    private String verificationUrl;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_verified_ip", length = 45)
    private String lastVerifiedIp;

    @Column(name = "verification_count", nullable = false)
    private Integer verificationCount = 0;

    @Column(name = "is_valid", nullable = false)
    private Boolean isValid = true;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;
}
