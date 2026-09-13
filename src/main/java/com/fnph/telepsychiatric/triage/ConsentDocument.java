package com.fnph.telepsychiatric.triage;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A version of the consent text.
 *
 * Versioned rather than edited. An acceptance points at the version that was
 * actually agreed to, so changing the wording next year does not silently
 * rewrite what every existing patient consented to.
 *
 * A retired version stays readable forever for the same reason.
 */
@Entity
@Table(name = "consent_documents")
@Getter
@Setter
public class ConsentDocument extends BaseEntity {

    @Column(name = "version", nullable = false, length = 30)
    private String version;

    @Column(name = "audience", nullable = false, length = 20)
    private String audience;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    /** DRAFT, PUBLISHED or RETIRED. Nothing can be accepted against a draft. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;
}
