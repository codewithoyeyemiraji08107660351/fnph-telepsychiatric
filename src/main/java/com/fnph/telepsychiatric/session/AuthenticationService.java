package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.email.AccountEmailService;
import com.fnph.telepsychiatric.security.CurrentSession;
import com.fnph.telepsychiatric.security.JwtService;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.session.api.*;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.UserStatus;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sign-in, second factor, refresh, activation and password reset.
 *
 * One rule runs through the whole class: **a failure never says which failure
 * it was.** Wrong password, unknown username, locked account, inactive account
 * and unactivated invitation all produce the same response and the same rough
 * timing. Distinguishing them turns the sign-in form into a way to discover
 * which EHR numbers and staff names hold accounts at a neuropsychiatric
 * hospital, which is a disclosure in itself. The real outcome is recorded in
 * login_attempts, where staff with the right permission can see it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final UserRepository userRepository;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final MfaService mfaService;
    private final AccountTokenRepository accountTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final AccountEmailService emailService;
    private final PasswordPolicy passwordPolicy;
    private final AuthProperties properties;
    private final com.fnph.telepsychiatric.notification.InAppNotificationService notifications;
    private final com.fnph.telepsychiatric.audit.AuditService auditService;

    static final String PATIENT_RESET_PREFIX = "patient-reset:";

    private static final String GENERIC_FAILURE =
            "Incorrect username or password. Please check your details and try again.";
    private static final String DUMMY_PASSWORD_HASH =
            new BCryptPasswordEncoder(12).encode("unused-sign-in-timing-placeholder");

    // -----------------------------------------------------------------
    // Sign-in
    // -----------------------------------------------------------------

    // AuthenticationFailedException is the expected outcome of a rejected sign-in.
    // Keep its audit row and account/factor lockout updates instead of rolling them back.
    // TooManyAttemptsException likewise: the refused attempt must still count.
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, TooManyAttemptsException.class})
    public LoginResponse login(LoginRequest request, RequestContext context) {
        String identifier = request.username().trim();

        // Was defined and never called, so one address could try a password
        // across any number of accounts. Patient usernames are EHR numbers,
        // which are not secret, so this check is what slows that down.
        if (isRateLimited(identifier, context.ipAddress())) {
            record(identifier, null, LoginOutcome.RATE_LIMITED, null, context);
            throw new TooManyAttemptsException("Too many sign-in attempts. Wait "
                    + properties.getFailureWindowMinutes() + " minutes and try again.");
        }

        Optional<Users> found = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier));

        if (found.isEmpty()) {
            // Still hash something, so a missing account does not return
            // measurably faster than a wrong password.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            record(identifier, null, LoginOutcome.UNKNOWN_USERNAME, null, context);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }

        Users user = found.get();

        if (user.getStatus() == UserStatus.INVITED) {
            record(identifier, user, LoginOutcome.ACCOUNT_NOT_ACTIVATED, null, context);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }
        if (!user.isAccountNonLocked()) {
            record(identifier, user, LoginOutcome.ACCOUNT_LOCKED, null, context);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }
        if (!user.isEnabled()) {
            record(identifier, user, LoginOutcome.ACCOUNT_INACTIVE, null, context);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            record(identifier, user, LoginOutcome.BAD_CREDENTIALS, null, context);
            applyLockoutIfNeeded(user);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }

        SecurityUser principal;
        try {
            principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getUsername());
        } catch (UsernameNotFoundException ex) {
            record(identifier, user, LoginOutcome.NO_ROLE_ASSIGNED, null, context);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }

        if (mfaService.isRequiredFor(principal.getScope())) {
            if (!mfaService.isEnrolled(user.getId())) {
                // A staff account must hold a second factor. Rather than
                // refusing sign-in outright, hand back a short-lived challenge
                // token that only permits enrolment. The user completes it now
                // and gets in; nothing else is reachable with that token.
                record(identifier, user, LoginOutcome.MFA_REQUIRED, "Enrolment required", context);
                return LoginResponse.mfaEnrolmentRequired(
                        challengeToken(principal), properties.getMfaChallengeMinutes() * 60L);
            }
            record(identifier, user, LoginOutcome.MFA_REQUIRED, null, context);
            return LoginResponse.mfaRequired(
                    challengeToken(principal), properties.getMfaChallengeMinutes() * 60L);
        }

        return completeLogin(user, principal, context);
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public LoginResponse verifyMfa(MfaVerificationRequest request, RequestContext context) {
        Users user = requireChallengeSubject(request.mfaToken());

        if (!mfaService.verifyChallenge(user, request.code(), context.ipAddress())) {
            record(user.getUsername(), user, LoginOutcome.MFA_FAILED, null, context);
            applyLockoutIfNeeded(user);
            throw new AuthenticationFailedException("That code is not correct.");
        }

        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getUsername());
        return completeLogin(user, principal, context);
    }

    @Transactional
    public MfaEnrolmentResponse beginMfaEnrolment(String mfaToken) {
        Users user = requireChallengeSubject(mfaToken);
        MfaService.Enrolment enrolment = mfaService.beginEnrolment(user);
        return new MfaEnrolmentResponse(enrolment.secret(), enrolment.provisioningUri(), null);
    }

    @Transactional
    public LoginResponse completeMfaEnrolment(MfaVerificationRequest request, RequestContext context) {
        Users user = requireChallengeSubject(request.mfaToken());
        List<String> recoveryCodes = mfaService.completeEnrolment(user, request.code());

        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getUsername());
        LoginResponse response = completeLogin(user, principal, context);
        return response.withRecoveryCodes(recoveryCodes);
    }

    // -----------------------------------------------------------------
    // Refresh and sign-out
    // -----------------------------------------------------------------

    @Transactional
    public LoginResponse refresh(String refreshToken, RequestContext context) {
        UserSession session = sessionService.rotate(refreshToken, context.ipAddress(), context.userAgent())
                .orElseThrow(() -> new AuthenticationFailedException(
                        "That session has ended. Sign in again."));

        Users user = session.getUser();
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getUsername());

        return LoginResponse.authenticated(
                jwtService.generateAccessToken(principal, session.getPublicId()),
                session.getTransientRawToken(),
                jwtService.getAccessTokenMinutes() * 60,
                principal);
    }

    @Transactional
    public void logout(String refreshToken) {
        sessionService.closeSession(refreshToken, "Signed out");
    }

    // -----------------------------------------------------------------
    // Activation and password reset
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public ActivationPreviewResponse previewActivation(String token) {
        AccountToken accountToken = usableToken(token, AccountTokenPurpose.ACTIVATION);
        Users user = accountToken.getUser();
        return new ActivationPreviewResponse(
                user.getFullName(), user.getEmail(), user.getUsername(),
                accountToken.getExpiresAt(), properties.getMinPasswordLength());
    }

    @Transactional
    public void completeActivation(CompleteActivationRequest request, RequestContext context) {
        AccountToken accountToken = usableToken(request.token(), AccountTokenPurpose.ACTIVATION);
        Users user = accountToken.getUser();

        List<String> problems = passwordPolicy.validate(
                request.password(), user.getUsername(), user.getEmail(), user.getFullName());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }

        LocalDateTime now = LocalDateTime.now();
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.ACTIVE);
        user.setIsActive(true);
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(now);
        user.setActivatedAt(now);
        // Following the link proves the address reaches this person, which is
        // what later allows a password reset to be sent to it.
        user.setEmailVerifiedAt(now);
        userRepository.save(user);

        accountToken.setUsedAt(now);
        accountToken.setUsedIp(context.ipAddress());
        accountTokenRepository.save(accountToken);

        log.info("Account {} activated", user.getPublicId());
    }

    /**
     * Always succeeds from the caller's point of view.
     *
     * Replying differently for a known and an unknown address turns this into
     * an address checker. The response, and roughly the timing, are identical
     * either way.
     */
    @Transactional
    public void requestPasswordReset(String identifier, RequestContext context) {
        Optional<Users> found = userRepository.findByUsernameIgnoreCase(identifier.trim())
                .or(() -> userRepository.findByEmailIgnoreCase(identifier.trim()));

        if (found.isEmpty()) {
            log.info("Password reset requested for an unknown identifier from {}", context.ipAddress());
            return;
        }
        Users user = found.get();

        // Patients use the EHR-only self-service reset endpoint. Do not send
        // password-reset notifications from this legacy email flow.
        if (user.getPatient() != null) {
            log.info("Patient password reset redirected to EHR self-service for {}", user.getPublicId());
            return;
        }

        // A patient account from online enrolment carries a placeholder address
        // and no verified-at, so every patient reset was silently skipped, and a
        // link would have gone nowhere. The address on the patient record is the
        // one the enrolment code was proved on: that is where the link goes.
        String sendTo = user.getEmail();
        boolean verified = user.getEmailVerifiedAt() != null;
        if (user.getPatient() != null) {
            String patientEmail = user.getPatient().getEmail();
            boolean real = patientEmail != null && patientEmail.contains("@")
                    && !patientEmail.trim().toLowerCase().endsWith(".local");
            sendTo = real ? patientEmail.trim() : null;
            verified = real;
        }

        if (user.getStatus() != UserStatus.ACTIVE || !verified || sendTo == null) {
            // An unverified address may be a typo made when the account was
            // created. Sending a reset link there would hand the account to
            // whoever owns that address.
            log.info("Password reset skipped for {}: account not active or no verified address",
                    user.getPublicId());
            return;
        }

        String raw = issueToken(user, AccountTokenPurpose.PASSWORD_RESET,
                LocalDateTime.now().plusMinutes(properties.getPasswordResetTokenMinutes()), context);

        emailService.sendPasswordReset(sendTo, user.getFullName(), raw,
                LocalDateTime.now().plusMinutes(properties.getPasswordResetTokenMinutes()),
                context.ipAddress());
    }

    /**
     * Starts an EHR-only patient reset and returns the short-lived credential
     * directly.
     *
     * The EHR number is the only check, as FNPH decided. It is printed on the
     * patient's card, so the limits here and the notice on completion are the
     * protection: a few resets per account a day, a few lookups per address an
     * hour, a ten-minute credential, an audit entry for every reset, and the
     * patient told every time their password changes.
     */
    // noRollbackFor: a refused lookup is still recorded, so it counts.
    @Transactional(noRollbackFor = {IllegalArgumentException.class, TooManyAttemptsException.class})
    public PatientPasswordResetResponse startPatientPasswordReset(
            PatientPasswordResetRequest request, RequestContext context) {
        String ehrNumber = request.ehrNumber().trim();
        LocalDateTime now = LocalDateTime.now();

        String ip = context.ipAddress();
        if (ip != null) {
            LocalDateTime hourAgo = now.minusHours(1);
            long fromAddress = accountTokenRepository.countIssuedFromIpSince(
                    ip, AccountTokenPurpose.PASSWORD_RESET, hourAgo)
                    + loginAttemptRepository.countPatientResetMissesFromIp(ip, hourAgo);
            if (fromAddress >= properties.getPatientResetsPerIpPerHour()) {
                throw new TooManyAttemptsException("Too many password resets from this connection. "
                        + "Wait an hour and try again, or contact the hospital helpdesk.");
            }
        }

        Optional<Users> found = userRepository.findByPatient_EhrNumber(ehrNumber)
                .filter(candidate -> candidate.getPatient() != null)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()));
        if (found.isEmpty()) {
            record(PATIENT_RESET_PREFIX + ehrNumber, null, LoginOutcome.UNKNOWN_USERNAME,
                    "Patient password reset: no active account", context);
            throw new IllegalArgumentException(
                    "No active patient account matches that EHR number. Check the number or enrol first.");
        }
        Users user = found.get();

        if (accountTokenRepository.countIssuedForUserSince(
                user.getId(), AccountTokenPurpose.PASSWORD_RESET, now.minusHours(24))
                >= properties.getPatientResetsPerAccountPerDay()) {
            throw new TooManyAttemptsException("This account's password has been reset several times "
                    + "today. For your safety, contact the hospital helpdesk to reset it.");
        }

        LocalDateTime expiresAt = now.plusMinutes(Math.min(
                properties.getPatientResetTokenMinutes(), properties.getPasswordResetTokenMinutes()));
        String raw = issueToken(user, AccountTokenPurpose.PASSWORD_RESET, expiresAt, context);
        return new PatientPasswordResetResponse(raw, expiresAt);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request, RequestContext context) {
        AccountToken accountToken = usableToken(request.token(), AccountTokenPurpose.PASSWORD_RESET);
        Users user = accountToken.getUser();

        List<String> problems = passwordPolicy.validate(
                request.password(), user.getUsername(), user.getEmail(), user.getFullName());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }

        LocalDateTime now = LocalDateTime.now();
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(now);
        user.setLastPasswordResetAt(now);
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLockExpiry(null);
        userRepository.save(user);

        accountToken.setUsedAt(now);
        accountToken.setUsedIp(context.ipAddress());
        accountTokenRepository.save(accountToken);

        // Whoever forced the reset may still hold a live session. End all of
        // them, including the current one.
        sessionService.revokeAll(user.getId(), null, "Password reset");

        auditService.record(com.fnph.telepsychiatric.audit.AuditService.AuditEvent.builder()
                .action(com.fnph.telepsychiatric.audit.AuditAction.PASSWORD_RESET)
                .entityType("Users").entityId(user.getId())
                .details(user.getPatient() != null
                        ? "Patient password reset by EHR number"
                        : "Password reset by link")
                .ipAddress(context.ipAddress()).userAgent(context.userAgent())
                .build());

        if (user.getPatient() == null) {
            emailService.sendPasswordChanged(user.getEmail(), user.getFullName(), now, context.ipAddress());
        } else {
            tellPatientPasswordChanged(user, now, context);
        }
    }

    /**
     * A patient reset needs only the EHR number, so the patient must hear
     * about every one: if it was not them, this is how they find out. It used
     * to send nothing at all.
     */
    private void tellPatientPasswordChanged(Users user, LocalDateTime now, RequestContext context) {
        try {
            notifications.notifyUser(user,
                    com.fnph.telepsychiatric.notification.NotificationType.SYSTEM_ALERT,
                    "Your password was changed",
                    "Your FNPH telepsychiatry password was changed on "
                            + com.fnph.telepsychiatric.common.HospitalClock.display(now)
                            + ". If this was not you, contact the hospital helpdesk straight away.",
                    "/portal/profile", "Users", user.getId());
        } catch (RuntimeException ex) {
            log.warn("Could not record the password-changed notice for {}", user.getPublicId(), ex);
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            emailService.sendPasswordChanged(user.getEmail(), user.getFullName(), now, context.ipAddress());
        }
    }

    @Transactional
    public void changePassword(SecurityUser principal, ChangePasswordRequest request,
                               RequestContext context) {
        Users user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new AuthenticationFailedException(GENERIC_FAILURE));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Your current password is not correct");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Choose a password you have not used here before");
        }

        List<String> problems = passwordPolicy.validate(
                request.newPassword(), user.getUsername(), user.getEmail(), user.getFullName());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }

        LocalDateTime now = LocalDateTime.now();
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(now);
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Keeps the current device signed in; ends every other one.
        sessionService.revokeAll(user.getId(), CurrentSession.id().orElse(null), "Password changed");

        emailService.sendPasswordChanged(user.getEmail(), user.getFullName(), now, context.ipAddress());
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    public String issueToken(Users user, AccountTokenPurpose purpose,
                             LocalDateTime expiresAt, RequestContext context) {
        accountTokenRepository.invalidateOutstanding(user.getId(), purpose, LocalDateTime.now());

        String raw = Tokens.generate();
        AccountToken token = new AccountToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(Tokens.hash(raw));
        token.setExpiresAt(expiresAt);
        token.setIssuedBy(context.actor());
        token.setIssuedIp(context.ipAddress());
        accountTokenRepository.save(token);
        return raw;
    }

    private LoginResponse completeLogin(Users user, SecurityUser principal, RequestContext context) {
        LocalDateTime now = LocalDateTime.now();

        user.setLastLoginAt(now);
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLockExpiry(null);
        userRepository.save(user);

        UserSession session = sessionService.openSession(
                user, context.ipAddress(), context.userAgent(), context.deviceLabel());

        record(user.getUsername(), user, LoginOutcome.SUCCESS, null, context);

        return LoginResponse.authenticated(
                jwtService.generateAccessToken(principal, session.getPublicId()),
                session.getTransientRawToken(),
                jwtService.getAccessTokenMinutes() * 60,
                principal);
    }

    /**
     * The token between password and second factor.
     *
     * A normal access token with a five-minute life. It is only accepted on the
     * MFA endpoints, so holding one without completing the challenge grants
     * nothing.
     */
    private String challengeToken(SecurityUser principal) {
        return jwtService.generateChallengeToken(principal, properties.getMfaChallengeMinutes());
    }

    private Users requireChallengeSubject(String mfaToken) {
        String username = jwtService.extractChallengeSubject(mfaToken)
                .orElseThrow(() -> new AuthenticationFailedException(
                        "That sign-in attempt has expired. Start again."));
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new AuthenticationFailedException(GENERIC_FAILURE));
    }

    private AccountToken usableToken(String raw, AccountTokenPurpose purpose) {
        return accountTokenRepository.findByTokenHashAndPurpose(Tokens.hash(raw), purpose)
                .filter(t -> t.isUsable(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "That link is no longer valid. It may have expired, already been used, "
                                + "or been replaced by a newer one. Request another."));
    }

    private boolean isRateLimited(String identifier, String ipAddress) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(properties.getFailureWindowMinutes());
        if (loginAttemptRepository.countRecentFailuresForUsername(identifier, since)
                >= properties.getMaxFailedAttempts()) {
            return true;
        }
        return ipAddress != null
                && loginAttemptRepository.countRecentFailuresForIp(ipAddress, since)
                >= properties.getMaxFailedAttemptsPerIp();
    }

    private void applyLockoutIfNeeded(Users user) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(properties.getFailureWindowMinutes());
        long failures = loginAttemptRepository.countRecentFailuresForUser(user.getId(), since);
        if (failures >= properties.getMaxFailedAttempts()) {
            user.setAccountLocked(true);
            user.setLockExpiry(LocalDateTime.now().plusMinutes(properties.getLockoutMinutes()));
            userRepository.save(user);
            log.warn("Account {} locked for {} minutes after {} failed attempts",
                    user.getPublicId(), properties.getLockoutMinutes(), failures);
        }
    }

    private void record(String identifier, Users user, LoginOutcome outcome,
                        String reason, RequestContext context) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setUsernameAttempted(identifier.length() > 150 ? identifier.substring(0, 150) : identifier);
        attempt.setUser(user);
        attempt.setOutcome(outcome);
        attempt.setFailureReason(reason);
        attempt.setIpAddress(context.ipAddress());
        attempt.setUserAgent(context.userAgent());
        attempt.setAttemptedAt(LocalDateTime.now());
        loginAttemptRepository.save(attempt);
    }

    /** Everything about the caller that the security decisions need. */
    public record RequestContext(String ipAddress, String userAgent,
                                 String deviceLabel, String actor) {
    }

    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException(String message) {
            super(message);
        }
    }
}
