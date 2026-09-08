package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One participant's permission to join one consultation.
 *
 * Stored hashed. The token itself is a bearer credential for a live clinical
 * consultation: anyone holding it joins the call, and there is no second factor
 * inside a video room. A leak of this table through a backup or a log must not
 * hand over working access.
 *
 * Bounded at both ends. Not valid before the join window opens, dead at the
 * slot end, so a forwarded link is worth nothing before or after.
 */
@Entity
@Table(name = "participant_tokens")
@Getter
@Setter
public class ParticipantToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id")
    private CentreConsultation centreConsultation;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false, length = 20)
    private ParticipantRole participantRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Shown to the other participant. The patient never sees the doctor's name. */
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "not_before", nullable = false)
    private LocalDateTime notBefore;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    @Column(name = "issued_ip", length = 45)
    private String issuedIp;

    public boolean isUsable(LocalDateTime now) {
        return revokedAt == null && now.isAfter(notBefore) && now.isBefore(expiresAt);
    }
}
