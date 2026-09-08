package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A scan of a QR code. Append-only.
 *
 * The verification endpoint is unauthenticated by necessity: a pharmacist
 * checking a prescription has no account here. A run of NOT_FOUND from one
 * address is the only signal that somebody is guessing tokens rather than
 * scanning documents.
 */
@Entity
@Table(name = "verification_attempts")
@Getter
@Setter
public class VerificationAttempt extends ImmutableEntity {

    @Column(name = "token_presented", nullable = false, length = 64)
    private String tokenPresented;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private VerificationOutcome outcome;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
