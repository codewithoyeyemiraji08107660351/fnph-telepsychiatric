package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every enrolment lookup. Append-only.
 *
 * The defence against walking the EHR number range, and the evidence if
 * somebody tries. A run of NOT_FOUND from one address is an enumeration
 * attempt and looks nothing like a patient mistyping their own number.
 */
@Entity
@Table(name = "ehr_lookup_attempts")
@Getter
@Setter
public class EhrLookupAttempt extends ImmutableEntity {

    @Column(name = "ehr_number_attempted", nullable = false, length = 50)
    private String ehrNumberAttempted;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private LookupOutcome outcome;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
