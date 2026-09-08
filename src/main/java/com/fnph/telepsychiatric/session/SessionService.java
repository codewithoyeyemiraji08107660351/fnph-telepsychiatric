package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.common.PublicId;
import com.fnph.telepsychiatric.email.AccountEmailService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.JwtProperties;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * The rotation rule is the important part. Every refresh mints a new token and
 * marks the old one replaced. If a replaced token is ever presented again, the
 * entire family is revoked and the user has to sign in.
 *
 * That is deliberately harsh. Presenting a replaced token means either a client
 * retried after a dropped response, or a stolen token is being used alongside
 * the real one. The server cannot tell the two apart, so it assumes the worse
 * case. Occasionally losing a session is a much better outcome than letting a
 * stolen token run for its full seven days on a system holding psychiatric
 * records.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final SessionRepository sessionRepository;
    private final JwtProperties jwtProperties;
    private final AccountEmailService emailService;

    /** A sign-in. Starts a new family. */
    @Transactional
    public UserSession openSession(Users user, String ipAddress, String userAgent, String deviceLabel) {
        LocalDateTime now = LocalDateTime.now();
        String familyId = PublicId.generate();

        boolean firstFromThisDevice = sessionRepository
                .findActiveByUser(user.getId(), now).stream()
                .noneMatch(s -> deviceLabel != null && deviceLabel.equals(s.getDeviceLabel()));

        UserSession session = persist(user, familyId, ipAddress, userAgent, deviceLabel, now);

        if (firstFromThisDevice && user.getEmail() != null) {
            emailService.sendNewDeviceNotice(
                    user.getEmail(), user.getFullName(), deviceLabel, ipAddress, now);
        }
        return session;
    }

    /**
     * Exchanges a refresh token for a new one.
     *
     * @return the new session, or empty when the token is unknown, expired,
     *         idle past the timeout, or already replaced. Empty always means
     *         "sign in again"; the caller never learns which of those it was,
     *         because the difference is only useful to someone probing.
     */
    @Transactional
    public Optional<UserSession> rotate(String presentedToken, String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now();

        Optional<UserSession> found =
                sessionRepository.findByRefreshTokenHash(Tokens.hash(presentedToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserSession session = found.get();

        // Reuse detection. This is the branch that matters.
        if (session.isReplaced() || session.isRevoked()) {
            int revoked = sessionRepository.revokeFamily(session.getFamilyId(), now, "system",
                    "Refresh token reuse detected");
            log.warn("Refresh token reuse on family {} for user {}. Revoked {} sessions.",
                    session.getFamilyId(), session.getUser().getId(), revoked);
            return Optional.empty();
        }

        if (session.isExpired(now)) {
            return Optional.empty();
        }
        if (session.isIdleBeyond(now, jwtProperties.getInactivityTimeoutMinutes())) {
            session.setRevokedAt(now);
            session.setRevokedBy("system");
            session.setRevokedReason("Inactivity timeout");
            sessionRepository.save(session);
            return Optional.empty();
        }

        UserSession replacement = persist(session.getUser(), session.getFamilyId(),
                ipAddress, userAgent, session.getDeviceLabel(), now);

        session.setReplacedAt(now);
        session.setReplacedBySessionId(replacement.getId());
        sessionRepository.save(session);

        return Optional.of(replacement);
    }

    /** Sign out on this device only. */
    @Transactional
    public void closeSession(String presentedToken, String reason) {
        sessionRepository.findByRefreshTokenHash(Tokens.hash(presentedToken))
                .filter(s -> !s.isRevoked())
                .ifPresent(s -> {
                    s.setRevokedAt(LocalDateTime.now());
                    s.setRevokedBy(CurrentUser.usernameOrSystem());
                    s.setRevokedReason(reason);
                    sessionRepository.save(s);
                });
    }

    @Transactional(readOnly = true)
    public List<UserSession> listActiveSessions(Long userId) {
        return sessionRepository.findActiveByUser(userId, LocalDateTime.now());
    }

    @Transactional
    public boolean revokeByPublicId(Long userId, String sessionPublicId, String reason) {
        return sessionRepository.findByPublicIdAndUserId(sessionPublicId, userId)
                .filter(s -> !s.isRevoked())
                .map(s -> {
                    s.setRevokedAt(LocalDateTime.now());
                    s.setRevokedBy(CurrentUser.usernameOrSystem());
                    s.setRevokedReason(reason);
                    sessionRepository.save(s);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Ends every session for an account.
     *
     * Called on password change, on reset, on deactivation and by an
     * administrator holding session.revoke. Password change keeps the current
     * session so the user is not signed out of the device they just used;
     * everything else keeps none.
     */
    @Transactional
    public int revokeAll(Long userId, Long exceptSessionId, String reason) {
        int count = sessionRepository.revokeAllForUser(userId, exceptSessionId,
                LocalDateTime.now(), CurrentUser.usernameOrSystem(), reason);
        log.info("Revoked {} sessions for user {}: {}", count, userId, reason);
        return count;
    }

    /** Advances lastSeenAt so an active session is not cut off by the idle timer. */
    @Transactional
    public void touch(Long sessionId) {
        sessionRepository.touch(sessionId, LocalDateTime.now());
    }

    private UserSession persist(Users user, String familyId, String ipAddress,
                                String userAgent, String deviceLabel, LocalDateTime now) {
        String rawToken = Tokens.generate();

        UserSession session = new UserSession();
        session.setUser(user);
        session.setFamilyId(familyId);
        session.setRefreshTokenHash(Tokens.hash(rawToken));
        session.setDeviceLabel(deviceLabel);
        session.setIpAddress(ipAddress);
        session.setUserAgent(truncate(userAgent, 500));
        session.setIssuedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plusDays(jwtProperties.getRefreshTokenDays()));

        UserSession saved = sessionRepository.save(session);

        // The raw token is returned to the caller once, in memory, and never
        // stored. Held on the transient field so the caller can read it without
        // a second lookup; it is not a mapped column.
        saved.setTransientRawToken(rawToken);
        return saved;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
