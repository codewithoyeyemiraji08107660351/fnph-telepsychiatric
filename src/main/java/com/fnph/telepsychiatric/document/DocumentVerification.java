package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_verifications")
@Getter
@Setter
public class DocumentVerification extends BaseEntity {

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "issue_number", nullable = false, length = 50)
    private String issueNumber;

    @Column(name = "verification_token", unique = true, nullable = false, length = 64)
    private String verificationToken;

    @Column(name = "qr_code", nullable = false, columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "verification_url", nullable = false, length = 500)
    private String verificationUrl;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by_ip", length = 45)
    private String verifiedByIp;

    @Column(name = "verification_count", nullable = false)
    private Integer verificationCount = 0;

    @Column(name = "is_valid", nullable = false)
    private Boolean isValid = true;
}
