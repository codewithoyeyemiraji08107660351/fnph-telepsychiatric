package com.fnph.telepsychiatric.session;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.security.crypto.TotpService;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Second factor enrolment and verification.
 *
 * Required for staff and centre accounts, which is the non-negotiable control
 * the specification states. Not required for patients: a patient enrolling a
 * TOTP app to attend a psychiatric appointment is a barrier that would stop
 * people attending, and their account cannot approve bookings, move money or
 * read anyone else's record. The risk and the friction are not in balance
 * there. Patient accounts get contact verification and rate limiting instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfaService {

    private final MfaFactorRepository factorRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final TotpService totpService;
    private final SecretEncryptor encryptor;
    private final AuthProperties properties;

    /** Staff and centre accounts must hold a second factor. Patients must not be forced to. */
    public boolean isRequiredFor(RoleScope scope) {
        return scope == RoleScope.FNPH || scope == RoleScope.CENTRE;
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(Long userId) {
        return factorRepository.findByUserIdAndTypeAndIsActiveTrue(userId, MfaType.TOTP).isPresent();
    }

    /**
     * Starts enrolment. Returns the secret and the otpauth URI for the QR code.
     *
     * The factor is created inactive. It only becomes usable once the user
     * proves they can generate a code, so an abandoned enrolment cannot lock
     * the account out.
     */
    @Transactional
    public Enrolment beginEnrolment(Users user) {
        factorRepository.findByUserIdAndType(user.getId(), MfaType.TOTP)
                .ifPresent(existing -> {
                    if (existing.isUsable()) {
                        throw new IllegalStateException(
                                "A second factor is already enrolled. Remove it before enrolling another.");
                    }
                    factorRepository.delete(existing);
                    factorRepository.flush();
                });

        String secret = totpService.generateSecret();

        MfaFactor factor = new MfaFactor();
        factor.setUser(user);
        factor.setType(MfaType.TOTP);
        factor.setSecretEncrypted(encryptor.encrypt(secret));
        factor.setDigits(6);
        factor.setPeriodSeconds(30);
        factor.setIsActive(false);
        factorRepository.save(factor);

        String uri = totpService.buildProvisioningUri(
                secret, user.getEmail(), properties.getMfaIssuer(), 6, 30);

        return new Enrolment(secret, uri);
    }

    /**
     * Confirms enrolment with a code from the app, then issues recovery codes.
     *
     * The codes are returned once, in clear, and stored hashed. If the user does
     * not save them and later loses their phone, an administrator has to reset
     * the factor. That is the correct trade: a recovery code retrievable later
     * is a second factor an attacker can retrieve too.
     */
    @Transactional
    public List<String> completeEnrolment(Users user, String code) {
        MfaFactor factor = factorRepository.findByUserIdAndType(user.getId(), MfaType.TOTP)
                .orElseThrow(() -> new IllegalStateException("Start enrolment first"));

        if (!totpService.verify(encryptor.decrypt(factor.getSecretEncrypted()), code,
                factor.getDigits(), factor.getPeriodSeconds())) {
            throw new IllegalArgumentException("That code is not correct. Check your device clock and try again.");
        }

        factor.setVerifiedAt(LocalDateTime.now());
        factor.setIsActive(true);
        factor.setFailedAttempts(0);
        factorRepository.save(factor);

        return regenerateRecoveryCodes(user);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(Users user) {
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        recoveryCodeRepository.flush();

        List<String> plain = new ArrayList<>();
        for (int i = 0; i < properties.getRecoveryCodeCount(); i++) {
            String code = Tokens.generateRecoveryCode();
            plain.add(code);

            MfaRecoveryCode entity = new MfaRecoveryCode();
            entity.setUser(user);
            entity.setCodeHash(Tokens.hash(code));
            recoveryCodeRepository.save(entity);
        }
        return plain;
    }

    /**
     * Verifies a challenge. Accepts a TOTP code or a recovery code.
     *
     * A used recovery code is marked immediately and never accepted again, so a
     * code read over someone's shoulder is worth one use at most.
     */
    @Transactional
    public boolean verifyChallenge(Users user, String code, String ipAddress) {
        Optional<MfaFactor> found =
                factorRepository.findByUserIdAndTypeAndIsActiveTrue(user.getId(), MfaType.TOTP);
        if (found.isEmpty()) {
            return false;
        }
        MfaFactor factor = found.get();

        if (factor.getLockedUntil() != null && factor.getLockedUntil().isAfter(LocalDateTime.now())) {
            return false;
        }

        String normalised = code == null ? "" : code.trim().toUpperCase();

        if (normalised.contains("-")) {
            Optional<MfaRecoveryCode> recovery =
                    recoveryCodeRepository.findByCodeHashAndUsedAtIsNull(Tokens.hash(normalised));
            if (recovery.isPresent() && recovery.get().getUser().getId().equals(user.getId())) {
                MfaRecoveryCode used = recovery.get();
                used.setUsedAt(LocalDateTime.now());
                used.setUsedIp(ipAddress);
                recoveryCodeRepository.save(used);

                long remaining = recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId());
                log.info("Recovery code used for user {}. {} remaining.", user.getId(), remaining);
                return true;
            }
            return recordFailure(factor);
        }

        boolean valid = totpService.verify(encryptor.decrypt(factor.getSecretEncrypted()),
                normalised, factor.getDigits(), factor.getPeriodSeconds());

        if (valid) {
            factor.setLastUsedAt(LocalDateTime.now());
            factor.setFailedAttempts(0);
            factor.setLockedUntil(null);
            factorRepository.save(factor);
            return true;
        }
        return recordFailure(factor);
    }

    /** Administrator reset for a lost device. Forces the user to enrol again. */
    @Transactional
    public void resetFactor(Long userId) {
        factorRepository.deleteByUserIdAndType(userId, MfaType.TOTP);
        recoveryCodeRepository.deleteAllByUserId(userId);
        log.info("Second factor reset for user {}", userId);
    }

    private boolean recordFailure(MfaFactor factor) {
        int attempts = factor.getFailedAttempts() + 1;
        factor.setFailedAttempts(attempts);
        if (attempts >= properties.getMaxMfaAttempts()) {
            factor.setLockedUntil(LocalDateTime.now().plusMinutes(properties.getMfaLockoutMinutes()));
            factor.setFailedAttempts(0);
            log.warn("Second factor locked for user {} after repeated failures", factor.getUser().getId());
        }
        factorRepository.save(factor);
        return false;
    }

    /** The secret and provisioning URI, returned once during enrolment. */
    public record Enrolment(String secret, String provisioningUri) {
    }
}
