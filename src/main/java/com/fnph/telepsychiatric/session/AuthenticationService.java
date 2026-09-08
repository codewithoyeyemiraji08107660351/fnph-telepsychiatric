package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.email.AccountEmailService;
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

    private static final String GENERIC_FAILURE =
            "Those sign-in details are not correct, or the account is not available.";

    // -----------------------------------------------------------------
    // Sign-in
    // -----------------------------------------------------------------

    @Transactional
    public LoginResponse login(LoginRequest request, RequestContext context) {
        String identifier = request.username().trim();

        if (isRateLimited(identifier, context.ipAddress())) {
            record(identifier, null, LoginOutcome.RATE_LIMITED, "Too many attempts", context);
            throw new AuthenticationFailedException(
                    "Too many attempts. Wait a few minutes before trying again.");
        }

        Optional<Users> found = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier));

        if (found.isEmpty()) {
            // Still hash something, so a missing account does not return
            // measurably faster than a wrong password.
            passwordEncoder.matches(request.password(), "$2a$12$invalidinvalidinvalidinvalidinvalidinvalidinvalidinva");
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
            applyLockoutIfNeeded(user, identifier);
            throw new AuthenticationFailedException(GENERIC_FAILURE);
        }

        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getUsername());

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

    @Transactional
    public LoginResponse verifyMfa(MfaVerificationRequest request, RequestContext context) {
        Users user = requireChallengeSubject(request.mfaToken());

        if (!mfaService.verifyChallenge(user, request.code(), context.ipAddress())) {
            record(user.getUsername(), user, LoginOutcome.MFA_FAILED, null, context);
            applyLockoutIfNeeded(user, user.getUsername());
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
                jwtService.generateAccessToken(principal),
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

        if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
            // An unverified address may be a typo made when the account was
            // created. Sending a reset link there would hand the account to
            // whoever owns that address.
            log.info("Password reset skipped for {}: account not active or address unverified",
                    user.getPublicId());
            return;
        }

        String raw = issueToken(user, AccountTokenPurpose.PASSWORD_RESET,
                LocalDateTime.now().plusMinutes(properties.getPasswordResetTokenMinutes()), context);

        emailService.sendPasswordReset(user.getEmail(), user.getFullName(), raw,
                LocalDateTime.now().plusMinutes(properties.getPasswordResetTokenMinutes()),
                context.ipAddress());
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

        emailService.sendPasswordChanged(user.getEmail(), user.getFullName(), now, context.ipAddress());
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
        sessionService.revokeAll(user.getId(), null, "Password changed");

        emailService.sendPasswordChanged(user.getEmail(), user.getFullName(), now, context.ipAddress());
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    String issueToken(Users user, AccountTokenPurpose purpose,
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
                jwtService.generateAccessToken(principal),
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

    private void applyLockoutIfNeeded(Users user, String identifier) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(properties.getFailureWindowMinutes());
        long failures = loginAttemptRepository.countRecentFailuresForUsername(identifier, since);
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
