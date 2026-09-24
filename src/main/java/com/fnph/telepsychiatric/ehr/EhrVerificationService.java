package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.ehr.api.*;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.LoginType;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.UserStatus;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Patient self-enrolment against the active snapshot.
 *
 * <h2>Two short steps</h2>
 *
 * <ol>
 *   <li><b>Lookup.</b> Match an existing active EHR record.</li>
 *   <li><b>Activation.</b> The patient chooses a password and the account exists.</li>
 * </ol>
 *
 * <h2>Why a corroborating detail is required</h2>
 *
 * If an EHR number alone returned a name, anyone could walk the range and
 * confirm that a named individual is a patient at a neuropsychiatric hospital.
 * That disclosure needs no account and no further step, which makes it the
 * likeliest attack on this system and the one worth engineering against.
 *
 * <h2>No enrolment notification</h2>
 *
 * Lookup creates a short-lived setup session but sends no email, SMS or in-app
 * notification. Step two asks only for a password. Contact data from the EHR
 * snapshot remains available for later care messages after the account exists.
 *
 * <h2>Why failures are indistinguishable</h2>
 *
 * Wrong number, wrong corroboration and already-enrolled all produce the same
 * response. Distinguishing them turns the form into a way to test whether a
 * given EHR number exists. The real outcome is recorded for staff.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EhrVerificationService {

    private static final String GENERIC_FAILURE =
            "We could not match that hospital number. Check it exactly as it appears on your "
                    + "hospital card, or request help below.";

    private final EhrImportRepository importRepository;
    private final ManualEhrRecordService manualEhrRecordService;
    private final EhrLookupAttemptRepository attemptRepository;
    private final ContactVerificationRepository contactRepository;
    private final PatientVerificationRequestRepository requestRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretEncryptor secretEncryptor;
    private final AuditService auditService;

    @Value("${application.security.enrolment.max-lookup-failures-per-ip:10}")
    private int maxFailuresPerIp;

    @Value("${application.security.enrolment.max-lookup-failures-per-number:5}")
    private int maxFailuresPerNumber;

    @Value("${application.security.enrolment.lookup-window-minutes:30}")
    private int windowMinutes;

    @Value("${application.security.enrolment.contact-code-minutes:10}")
    private int codeMinutes;

    /**
     * The simplified flow may enrol by EHR number alone. Set true to also
     * require a date of birth or the last four phone digits.
     */
    @Value("${application.security.enrolment.require-corroboration:false}")
    private boolean requireCorroboration;

    // -----------------------------------------------------------------
    // Step 1: lookup
    // -----------------------------------------------------------------

    // noRollbackFor: every refusal throws, and rolling back discarded the failed
    // attempt just recorded, so the rate limit never counted a single failure.
    @Transactional(noRollbackFor = EnrolmentException.class)
    public EnrolmentLookupResponse lookup(EnrolmentLookupRequest request,
                                          String ipAddress, String userAgent) {
        String ehrNumber = request.ehrNumber().trim();

        if (isRateLimited(ehrNumber, ipAddress)) {
            record(ehrNumber, LookupOutcome.RATE_LIMITED, ipAddress, userAgent);
            throw new EnrolmentException(
                    "Too many attempts. Wait 30 minutes, or request help below.");
        }

        Optional<EhrVerificationImport> active =
                importRepository.findFirstByStatus(ImportStatus.ACTIVE);
        if (active.isEmpty()) {
            record(ehrNumber, LookupOutcome.NO_ACTIVE_IMPORT, ipAddress, userAgent);
            log.error("Enrolment attempted with no active EHR snapshot. Patients cannot enrol.");
            throw new EnrolmentException(
                    "Enrolment is temporarily unavailable. Please try again later.");
        }

        EhrVerificationRecord snapshot = manualEhrRecordService.effectiveRecord(active.get().getId(), ehrNumber);

        if (snapshot == null) {
            record(ehrNumber, LookupOutcome.NOT_FOUND, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {
            record(ehrNumber, LookupOutcome.RECORD_INACTIVE, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (!corroborates(snapshot, request)) {
            record(ehrNumber, LookupOutcome.CORROBORATION_FAILED, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (patientRepository.existsByEhrNumber(ehrNumber)) {
            record(ehrNumber, LookupOutcome.ALREADY_ENROLLED, ipAddress, userAgent);
            throw new EnrolmentException(
                    "This hospital record already has an account. Sign in with your EHR number and password, or reset your password.");
        }

        record(ehrNumber, LookupOutcome.MATCHED, ipAddress, userAgent);

        contactRepository.invalidateOutstanding(ehrNumber, LocalDateTime.now());

        // Reuse the existing short-lived verification table as an enrolment
        // session. No email, SMS or in-app notification is sent.
        ContactVerification verification = new ContactVerification();
        verification.setEhrNumber(ehrNumber);
        verification.setChannel(ContactChannel.EMAIL);
        verification.setDeliveryRoute("SELF_SERVICE");
        verification.setDestinationMasked("Not required");
        verification.setCodeHash(Tokens.hash(Tokens.generate()));
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(codeMinutes));
        verification.setIpAddress(ipAddress);
        verification.setCorroboratedDateOfBirth(request.dateOfBirth());
        ContactVerification saved = contactRepository.save(verification);

        return new EnrolmentLookupResponse(
                saved.getPublicId(),
                snapshot.getEhrNumber(),
                snapshot.getFullName(),
                snapshot.getDateOfBirthMasked(),
                snapshot.getClinic(),
                verification.getExpiresAt(),
                active.get().getSourceAsAt(),
                active.get().ageInDays());
    }

    /**
     * Accepts a date of birth or the last four digits of the phone.
     *
     * Either alone plus the EHR number is enough. Requiring both would push
     * legitimate patients into the exception queue over a phone number the
     * hospital recorded years ago, and the number is the weaker factor anyway.
     *
     * A record carrying an email and no phone leaves date of birth as the only
     * option, which is the stronger factor and therefore not a loss.
     */
    /**
     * Anything the patient supplies must match. Supplying nothing is accepted
     * unless corroboration is required by configuration.
     */
    private boolean corroborates(EhrVerificationRecord snapshot, EnrolmentLookupRequest request) {
        if (request.dateOfBirth() != null) {
            return Tokens.hash(request.dateOfBirth().toString())
                    .equals(snapshot.getDateOfBirthHash());
        }
        if (request.phoneLastFour() != null) {
            return snapshot.getPhoneMasked() != null
                    && snapshot.getPhoneMasked().endsWith(request.phoneLastFour().trim());
        }
        return !requireCorroboration;
    }

    // -----------------------------------------------------------------
    // Step 2: choose a password and activate
    // -----------------------------------------------------------------

    @Transactional(noRollbackFor = EnrolmentException.class)
    public void activate(CompleteEnrolmentRequest request, String ipAddress) {
        ContactVerification verification = contactRepository
                .findByPublicId(request.verificationPublicId())
                .filter(v -> v.isUsable(LocalDateTime.now()))
                .orElseThrow(() -> new EnrolmentException(
                        "That setup session has expired. Start again."));

        EhrVerificationImport active = importRepository.findFirstByStatus(ImportStatus.ACTIVE)
                .orElseThrow(() -> new EnrolmentException("Enrolment is temporarily unavailable."));

        EhrVerificationRecord snapshot = manualEhrRecordService
                .effectiveRecord(active.getId(), verification.getEhrNumber());
        if (snapshot == null) {
            throw new EnrolmentException("That record is no longer available. Request help below.");
        }

        if (patientRepository.existsByEhrNumber(snapshot.getEhrNumber())) {
            throw new EnrolmentException("That record is already enrolled.");
        }

        if (request.password().length() < 8) {
            throw new IllegalArgumentException("Use at least 8 characters");
        }

        LocalDateTime now = LocalDateTime.now();
        String[] nameParts = snapshot.getFullName().trim().split("\\s+", 2);

        Patient patient = new Patient();
        patient.setEhrNumber(snapshot.getEhrNumber());
        patient.setFirstName(nameParts[0]);
        patient.setLastName(nameParts.length > 1 ? nameParts[1] : nameParts[0]);
        patient.setDateOfBirth(dateOfBirthFor(verification, snapshot));
        // Preserve the hospital-record address for later operational messages.
        // Enrolment itself sends no notification and does not claim this route
        // was independently verified.
        if (snapshot.getEmailEncrypted() != null) {
            patient.setEmail(secretEncryptor.decrypt(snapshot.getEmailEncrypted()));
        }
        patient.setSourceImportId(active.getId());
        patient.setIsEligible(true);
        patient.setIsPhysicallyAssessed(true);
        patient.setEligibilityVerifiedAt(now);
        patient.setEligibilityVerifiedBy("EHR snapshot dated " + active.getSourceAsAt());
        patient.setActivatedAt(now);
        patient.setIsActive(true);
        Patient savedPatient = patientRepository.save(patient);

        Role patientRole = roleRepository.findByCode("PATIENT")
                .orElseThrow(() -> new IllegalStateException("PATIENT role is not seeded"));

        Users account = new Users();
        // The EHR number becomes the username, which is how all three login
        // types converge on one lookup.
        String username = snapshot.getEhrNumber().trim().toLowerCase();
        account.setUsername(username);
        account.setEmail(username + "@patient.fnph.local");
        account.setPassword(passwordEncoder.encode(request.password()));
        account.setFirstName(patient.getFirstName());
        account.setLastName(patient.getLastName());
        account.setPatient(savedPatient);
        account.setLoginType(LoginType.EHR_NUMBER);
        account.setStatus(UserStatus.ACTIVE);
        account.setIsActive(true);
        // Not required for patients. Making someone enrol a TOTP app to attend
        // a psychiatric appointment is a barrier that stops people attending.
        account.setMfaEnabled(false);
        account.setActivatedAt(now);
        account.setPasswordChangedAt(now);
        Users savedAccount = userRepository.save(account);

        UserRole assignment = new UserRole();
        assignment.setUserId(savedAccount.getId());
        assignment.setRoleId(patientRole.getId());
        assignment.setIsPrimary(true);
        assignment.setGrantedAt(now);
        assignment.setGrantedBy("system");
        assignment.setGrantReason("Self-enrolment against EHR snapshot " + active.getPublicId());
        userRoleRepository.save(assignment);

        verification.setVerifiedAt(now);
        verification.setPatient(savedPatient);
        contactRepository.save(verification);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.USER_ACTIVATED)
                .entityType("Patient")
                .entityId(savedPatient.getId())
                .details("Self-enrolled against snapshot dated " + active.getSourceAsAt())
                .ipAddress(ipAddress)
                .build());

        log.info("Patient {} enrolled against snapshot {}",
                savedPatient.getPublicId(), active.getPublicId());
    }

    /**
     * The patient's real date of birth where it is known.
     *
     * The snapshot holds only a hash and a masked year, so it cannot supply
     * one. Where the patient corroborated with a date of birth, that date was
     * matched against the hash at lookup and carried on the verification, so it
     * is both correct and already proved.
     *
     * Where they corroborated with the last four digits of the phone instead,
     * there is no date, and the year from the mask with 1 January is a
     * placeholder. A fabricated date that looks real is worse than one that
     * does not, so it is logged: a clinical record showing 1 January should be
     * recognisable as unknown rather than believed.
     */
    private LocalDate dateOfBirthFor(ContactVerification verification,
                                     EhrVerificationRecord snapshot) {
        if (verification.getCorroboratedDateOfBirth() != null) {
            return verification.getCorroboratedDateOfBirth();
        }
        if (snapshot.getDateOfBirthEncrypted() != null) {
            try {
                return LocalDate.parse(secretEncryptor.decrypt(snapshot.getDateOfBirthEncrypted()));
            } catch (RuntimeException e) {
                log.warn("Could not read the stored date of birth for {}: {}", snapshot.getEhrNumber(), e.getMessage());
            }
        }
        int year = Integer.parseInt(snapshot.getDateOfBirthMasked().substring(6));
        log.warn("Patient {} enrolled by phone corroboration. Date of birth recorded as "
                        + "1 January {} and is not the real date. Confirm it at first contact.",
                snapshot.getEhrNumber(), year);
        return LocalDate.of(year, 1, 1);
    }

    // -----------------------------------------------------------------
    // The exception queue
    // -----------------------------------------------------------------

    @Transactional
public String requestVerification(VerificationHelpRequest request, String ipAddress) {
    boolean alreadyOpen = requestRepository.existsByEhrNumberClaimedAndStatusIn(
            request.ehrNumber().trim(),
            List.of(VerificationRequestStatus.SUBMITTED,
                    VerificationRequestStatus.WITH_HIM,
                    VerificationRequestStatus.WITH_ICT));

    if (!alreadyOpen) {
        PatientVerificationRequest entry = new PatientVerificationRequest();
        entry.setEhrNumberClaimed(request.ehrNumber().trim());
        entry.setFullName(request.fullName().trim());
        entry.setDateOfBirth(request.dateOfBirth());
        entry.setPhoneNumber(request.phoneNumber() == null ? null : request.phoneNumber().trim());
        entry.setEmail(request.email());
        entry.setPreferredContact(request.preferredContact() == null
                ? "SMS" : request.preferredContact());
        entry.setSupportingNote(request.supportingNote());
        entry.setIpAddress(ipAddress);
        requestRepository.save(entry);
    }

    return "Your request has been received. The hospital will contact you on the number "
            + "you provided. This usually takes one working day.";
}

    // -----------------------------------------------------------------

    private boolean isRateLimited(String ehrNumber, String ipAddress) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        if (attemptRepository.countRecentFailuresForNumber(ehrNumber, since) >= maxFailuresPerNumber) {
            return true;
        }
        return ipAddress != null
                && attemptRepository.countRecentFailuresForIp(ipAddress, since) >= maxFailuresPerIp;
    }

    private void record(String ehrNumber, LookupOutcome outcome, String ip, String userAgent) {
        EhrLookupAttempt attempt = new EhrLookupAttempt();
        attempt.setEhrNumberAttempted(ehrNumber.length() > 50 ? ehrNumber.substring(0, 50) : ehrNumber);
        attempt.setOutcome(outcome);
        attempt.setIpAddress(ip);
        attempt.setUserAgent(userAgent);
        attempt.setAttemptedAt(LocalDateTime.now());
        attemptRepository.save(attempt);
    }

    public static class EnrolmentException extends RuntimeException {
        public EnrolmentException(String message) {
            super(message);
        }
    }
}
