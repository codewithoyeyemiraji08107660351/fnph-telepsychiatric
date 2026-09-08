package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A one-time code proving the contact route reaches the person enrolling.
 *
 * Sent to the number held in the snapshot, never to one the caller supplies.
 * Otherwise anyone who learned an EHR number and a date of birth could point
 * the account at their own phone.
 */
@Entity
@Table(name = "contact_verifications")
@Getter
@Setter
public class ContactVerification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "ehr_number", nullable = false, length = 50)
    private String ehrNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private ContactChannel channel;

    /** Shown to the caller so they know where to look. Never the full value. */
    @Column(name = "destination_masked", nullable = false, length = 50)
    private String destinationMasked;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public boolean isUsable(LocalDateTime now) {
        return verifiedAt == null && invalidatedAt == null && expiresAt.isAfter(now);
    }
}
