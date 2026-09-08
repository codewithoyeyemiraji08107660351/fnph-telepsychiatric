package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every download attempt, allowed or refused. Append-only.
 *
 * A patient saying "it only let me download once and I never actually got it"
 * is settled here, and so is the reverse.
 */
@Entity
@Table(name = "document_download_events")
@Getter
@Setter
public class DocumentDownloadEvent extends ImmutableEntity {

    @Column(name = "issued_document_id", nullable = false)
    private Long issuedDocumentId;

    @Column(name = "downloaded_by", length = 100)
    private String downloadedBy;

    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private DownloadOutcome outcome;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
