package com.fnph.telepsychiatric.triage;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.ImmutableEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One act of consenting. Append-only.
 *
 * What somebody agreed to cannot be edited afterwards, and the version is
 * recorded on the row rather than only as a reference, so the record survives
 * even if the document is later retired.
 */
@Entity
@Table(name = "consent_acceptances")
@Getter
@Setter
public class ConsentAcceptance extends ImmutableEntity {
    @Column(name = "typed_signature", length = 150)
    private String typedSignature;
    @Column(name = "declarations_json", columnDefinition = "TEXT")
    private String declarationsJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_document_id", nullable = false)
    private ConsentDocument consentDocument;

    @Column(name = "consent_version", nullable = false, length = 30)
    private String consentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id")
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by", nullable = false, length = 150)
    private String acceptedBy;

    /** For a centre patient, who was in the room. A browser cannot witness. */
    @Column(name = "witnessed_by", length = 150)
    private String witnessedBy;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
