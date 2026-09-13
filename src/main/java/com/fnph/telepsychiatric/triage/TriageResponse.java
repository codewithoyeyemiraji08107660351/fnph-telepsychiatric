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
 * One completed triage. Append-only.
 *
 * A patient who answered yes to a risk question and then submitted again with
 * no leaves both in the record. That pattern is clinically interesting and
 * deleting the first answer would hide it.
 *
 * A STOPPED outcome is a redirection, not a rejection. The escalation text
 * shown to the patient is stored so a later reader knows what they were
 * actually told.
 */
@Entity
@Table(name = "triage_responses")
@Getter
@Setter
public class TriageResponse extends ImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_set_id", nullable = false)
    private TriageQuestionSet questionSet;

    @Column(name = "triage_version", nullable = false, length = 30)
    private String triageVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_patient_id")
    private CentrePatient centrePatient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id")
    private Center centre;

    /** Every answer as given, so the whole submission is reconstructable. */
    @Column(name = "answers_json", nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "stopped_on_question_id")
    private Long stoppedOnQuestionId;

    @Column(name = "stop_reason", length = 200)
    private String stopReason;

    @Column(name = "escalation_shown", columnDefinition = "TEXT")
    private String escalationShown;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
