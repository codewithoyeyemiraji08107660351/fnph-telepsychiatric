package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A recorded session.
 *
 * Modelled, and disabled. Rooms are created with recording switched off at the
 * provider unless configuration says otherwise, and configuration says
 * otherwise only when FNPH have approved consent, retention, access, data
 * location, deletion and incident response.
 *
 * The consent reference is not optional here for a reason: a recording without
 * a recorded consent is evidence of a conversation nobody agreed to record,
 * and on psychiatric consultations that is the worst artefact this system
 * could produce.
 */
@Entity
@Table(name = "recordings")
@Getter
@Setter
public class Recording extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_consultation_id")
    private CentreConsultation centreConsultation;

    @Column(name = "provider_recording_id", length = 150)
    private String providerRecordingId;

    @Column(name = "storage_bucket", length = 100)
    private String storageBucket;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    /** Which consent version every participant accepted. */
    @Column(name = "consent_acceptance_id")
    private Long consentAcceptanceId;

    /** Every participant was told, and the recording state was unmistakable. */
    @Column(name = "all_participants_notified", nullable = false)
    private Boolean allParticipantsNotified = false;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "retention_expires_at")
    private LocalDateTime retentionExpiresAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;
}
