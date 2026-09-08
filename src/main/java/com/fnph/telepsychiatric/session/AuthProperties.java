package com.fnph.telepsychiatric.session;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.security.auth")
public class AuthProperties {

    /** Failed sign-ins against one username before it locks. */
    private int maxFailedAttempts = 5;

    /** Window the failures are counted over. */
    private int failureWindowMinutes = 15;

    /** How long a locked account stays locked. */
    private int lockoutMinutes = 30;

    /**
     * Failed attempts from one address across all usernames.
     *
     * Higher than the per-username threshold because a shared hospital
     * connection puts many legitimate users behind one address, and locking it
     * would take a whole department offline.
     */
    private int maxFailedAttemptsPerIp = 30;

    /** Invitation links are long-lived: a new member of staff may not be at a desk today. */
    private int activationTokenHours = 72;

    /** Reset links are short-lived: the user asked for it seconds ago. */
    private int passwordResetTokenMinutes = 60;

    /** Wrong second-factor codes before the factor locks. Lower than the password threshold: six digits is a small space. */
    private int maxMfaAttempts = 5;

    private int mfaLockoutMinutes = 15;

    /** How long the intermediate token between password and second factor lives. */
    private int mfaChallengeMinutes = 5;

    private int recoveryCodeCount = 10;

    /** Minimum password length. Length beats composition rules; see PasswordPolicy. */
    private int minPasswordLength = 12;

    private String mfaIssuer = "FNPH Kaduna Telepsychiatry";
}
