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
import java.util.Locale;
import java.util.Optional;

/**
 * Patient self-enrolment against the active EHR snapshot.
 *
 * <h2>Two short steps</h2>
 *
 * <ol>
 *   <li><b>Lookup.</b> Match an existing active EHR record by EHR number.</li>
 *   <li><b>Activation.</b> The patient chooses a password and the account exists.</li>
 * </ol>
 *
 * <h2>EHR-number-only enrolment</h2>
 *
 * The EHR number is the only value required to find and verify the patient's
 * active hospital record. Date of birth and phone digits are not used as
 * corroborating factors during self-enrolment.
 *
 * <h2>No enrolment notification</h2>
 *
 * Lookup creates a short-lived setup session but sends no email, SMS or
 * in-app notification. Step two asks only for a password.
 *
 * <h2>Why failures are indistinguishable</h2>
 *
 * Wrong number and other lookup failures produce the same generic response.
 * The actual outcome is recorded internally for staff and rate limiting.
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

    // -----------------------------------------------------------------
    // Step 1: lookup
    // -----------------------------------------------------------------

    /**
     * Looks up an active EHR record using the EHR number only.
     *
     * No DOB, phone number, email address or other corroborating field is
     * required or checked.
     *
     * noRollbackFor is intentional: failed attempts must remain persisted so
     * that rate limiting actually counts them.
     */
    @Transactional(noRollbackFor = EnrolmentException.class)
    public EnrolmentLookupResponse lookup(
            EnrolmentLookupRequest request,
            String ipAddress,
            String userAgent) {

        String ehrNumber = normalizeEhrNumber(request.ehrNumber());

        if (ehrNumber == null) {
            record(null, LookupOutcome.NOT_FOUND, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (isRateLimited(ehrNumber, ipAddress)) {
            record(ehrNumber, LookupOutcome.RATE_LIMITED, ipAddress, userAgent);
            throw new EnrolmentException(
                    "Too many attempts. Wait 30 minutes, or request help below.");
        }

        Optional<EhrVerificationImport> active =
                importRepository.findFirstByStatus(ImportStatus.ACTIVE);

        if (active.isEmpty()) {
            record(ehrNumber, LookupOutcome.NO_ACTIVE_IMPORT, ipAddress, userAgent);

            log.error(
                    "Enrolment attempted with no active EHR snapshot. Patients cannot enrol.");

            throw new EnrolmentException(
                    "Enrolment is temporarily unavailable. Please try again later.");
        }

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        active.get().getId(),
                        ehrNumber);

        if (snapshot == null) {
            record(ehrNumber, LookupOutcome.NOT_FOUND, ipAddress, userAgent);

            log.info(
                    "EHR lookup did not match snapshot: number='{}', importId={}",
                    maskEhrNumber(ehrNumber),
                    active.get().getId());

            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {
            record(ehrNumber, LookupOutcome.RECORD_INACTIVE, ipAddress, userAgent);

            log.info(
                    "EHR lookup matched an inactive record: number='{}', importId={}",
                    maskEhrNumber(ehrNumber),
                    active.get().getId());

            throw new EnrolmentException(GENERIC_FAILURE);
        }

        /*
         * EHR number is the only enrolment lookup credential.
         *
         * Do NOT add DOB, phone, email or any other corroboration here.
         */

        if (patientRepository.existsByEhrNumber(ehrNumber)) {
            record(ehrNumber, LookupOutcome.ALREADY_ENROLLED, ipAddress, userAgent);

            throw new EnrolmentException(
                    "This hospital record already has an account. "
                            + "Sign in with your EHR number and password, or reset your password.");
        }

        record(ehrNumber, LookupOutcome.MATCHED, ipAddress, userAgent);

        contactRepository.invalidateOutstanding(
                ehrNumber,
                LocalDateTime.now());

        /*
         * Reuse the existing short-lived verification table as the enrolment
         * setup session.
         *
         * No email, SMS or in-app notification is sent.
         *
         * The generated code remains stored because the existing entity/schema
         * expects it, but it is not delivered to the patient.
         */
        ContactVerification verification = new ContactVerification();

        verification.setEhrNumber(ehrNumber);
        verification.setChannel(ContactChannel.EMAIL);
        verification.setDeliveryRoute("SELF_SERVICE");
        verification.setDestinationMasked("Not required");
        verification.setCodeHash(Tokens.hash(Tokens.generate()));
        verification.setExpiresAt(
                LocalDateTime.now().plusMinutes(codeMinutes));
        verification.setIpAddress(ipAddress);

        /*
         * EHR-number-only flow:
         * the patient did not prove DOB during lookup, so never copy a
         * client-supplied DOB into the verification session.
         */
        verification.setCorroboratedDateOfBirth(null);

        ContactVerification saved =
                contactRepository.save(verification);

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

    // -----------------------------------------------------------------
    // Step 2: choose a password and activate
    // -----------------------------------------------------------------

    @Transactional(noRollbackFor = EnrolmentException.class)
    public void activate(
            CompleteEnrolmentRequest request,
            String ipAddress) {

        ContactVerification verification =
                contactRepository
                        .findByPublicId(request.verificationPublicId())
                        .filter(v -> v.isUsable(LocalDateTime.now()))
                        .orElseThrow(() -> new EnrolmentException(
                                "That setup session has expired. Start again."));

        EhrVerificationImport active =
                importRepository
                        .findFirstByStatus(ImportStatus.ACTIVE)
                        .orElseThrow(() -> new EnrolmentException(
                                "Enrolment is temporarily unavailable."));

        String ehrNumber = normalizeEhrNumber(
                verification.getEhrNumber());

        if (ehrNumber == null) {
            throw new EnrolmentException(
                    "That setup session is invalid. Start again.");
        }

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        active.getId(),
                        ehrNumber);

        if (snapshot == null) {
            throw new EnrolmentException(
                    "That record is no longer available. Request help below.");
        }

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {
            throw new EnrolmentException(
                    "That record is no longer available. Request help below.");
        }

        if (patientRepository.existsByEhrNumber(snapshot.getEhrNumber())) {
            throw new EnrolmentException(
                    "That record is already enrolled.");
        }

        if (request.password().length() < 8) {
            throw new IllegalArgumentException(
                    "Use at least 8 characters");
        }

        LocalDateTime now = LocalDateTime.now();

        String fullName = snapshot.getFullName() == null
                ? ""
                : snapshot.getFullName().trim();

        if (fullName.isBlank()) {
            throw new EnrolmentException(
                    "The hospital record has no patient name. Request help below.");
        }

        String[] nameParts = fullName.split("\\s+", 2);

        Patient patient = new Patient();

        patient.setEhrNumber(snapshot.getEhrNumber());
        patient.setFirstName(nameParts[0]);
        patient.setLastName(
                nameParts.length > 1
                        ? nameParts[1]
                        : nameParts[0]);

        /*
         * DOB is obtained from the EHR snapshot only.
         *
         * The patient did not corroborate DOB during lookup, therefore
         * verification.getCorroboratedDateOfBirth() is expected to be null.
         */
        patient.setDateOfBirth(
                dateOfBirthFromSnapshot(snapshot));

        /*
         * Preserve the hospital-record email for later operational messages.
         * Enrolment itself sends no notification.
         */
        if (snapshot.getEmailEncrypted() != null) {
            patient.setEmail(
                    secretEncryptor.decrypt(
                            snapshot.getEmailEncrypted()));
        }

        patient.setSourceImportId(active.getId());
        patient.setIsEligible(true);
        patient.setIsPhysicallyAssessed(true);
        patient.setEligibilityVerifiedAt(now);
        patient.setEligibilityVerifiedBy(
                "EHR snapshot dated " + active.getSourceAsAt());
        patient.setActivatedAt(now);
        patient.setIsActive(true);

        Patient savedPatient =
                patientRepository.save(patient);

        Role patientRole =
                roleRepository.findByCode("PATIENT")
                        .orElseThrow(() -> new IllegalStateException(
                                "PATIENT role is not seeded"));

        Users account = new Users();

        /*
         * The EHR number is the patient's username.
         *
         * Keep the normalized EHR value consistent with lookup and activation.
         */
        String username =
                snapshot.getEhrNumber()
                        .trim()
                        .toLowerCase(Locale.ROOT);

        account.setUsername(username);
        account.setEmail(
                username + "@patient.fnph.local");
        account.setPassword(
                passwordEncoder.encode(request.password()));
        account.setFirstName(
                patient.getFirstName());
        account.setLastName(
                patient.getLastName());
        account.setPatient(savedPatient);
        account.setLoginType(LoginType.EHR_NUMBER);
        account.setStatus(UserStatus.ACTIVE);
        account.setIsActive(true);

        /*
         * MFA is not required for patient self-enrolment.
         */
        account.setMfaEnabled(false);
        account.setActivatedAt(now);
        account.setPasswordChangedAt(now);

        Users savedAccount =
                userRepository.save(account);

        UserRole assignment = new UserRole();

        assignment.setUserId(savedAccount.getId());
        assignment.setRoleId(patientRole.getId());
        assignment.setIsPrimary(true);
        assignment.setGrantedAt(now);
        assignment.setGrantedBy("system");
        assignment.setGrantReason(
                "Self-enrollment against EHR snapshot "
                        + active.getPublicId());

        userRoleRepository.save(assignment);

        verification.setVerifiedAt(now);
        verification.setPatient(savedPatient);

        contactRepository.save(verification);

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(AuditAction.USER_ACTIVATED)
                        .entityType("Patient")
                        .entityId(savedPatient.getId())
                        .details(
                                "Self-enrolled against snapshot dated "
                                        + active.getSourceAsAt())
                        .ipAddress(ipAddress)
                        .build());

        log.info(
                "Patient {} enrolled against snapshot {}",
                savedPatient.getPublicId(),
                active.getPublicId());
    }

    /**
     * Gets the patient's real DOB from the EHR snapshot.
     *
     * The encrypted DOB is preferred because it contains the actual date.
     * If it is unavailable, the masked year is converted to 1 January of
     * that year. That fallback is explicitly logged because it is not the
     * patient's verified day/month.
     */
    private LocalDate dateOfBirthFromSnapshot(
            EhrVerificationRecord snapshot) {

        if (snapshot.getDateOfBirthEncrypted() != null) {
            try {
                return LocalDate.parse(
                        secretEncryptor.decrypt(
                                snapshot.getDateOfBirthEncrypted()));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not read the stored date of birth for {}: {}",
                        snapshot.getEhrNumber(),
                        e.getMessage());
            }
        }

        String masked = snapshot.getDateOfBirthMasked();

        if (masked == null || masked.length() < 4) {
            throw new EnrolmentException(
                    "The hospital record does not contain a usable date of birth. "
                            + "Request help below.");
        }

        try {
            /*
             * Expected masked format is the existing YYYY-**-** style.
             * Extract the four-digit year safely.
             */
            int year = Integer.parseInt(
                    masked.substring(0, 4));

            log.warn(
                    "Patient {} enrolled using masked DOB year {}. "
                            + "The actual day and month are not available in the snapshot.",
                    snapshot.getEhrNumber(),
                    year);

            return LocalDate.of(year, 1, 1);

        } catch (RuntimeException e) {
            throw new EnrolmentException(
                    "The hospital record does not contain a usable date of birth. "
                            + "Request help below.");
        }
    }

    // -----------------------------------------------------------------
    // The exception queue
    // -----------------------------------------------------------------

    @Transactional
    public String requestVerification(
            VerificationHelpRequest request,
            String ipAddress) {

        String ehrNumber = normalizeEhrNumber(
                request.ehrNumber());

        boolean alreadyOpen =
                requestRepository.existsByEhrNumberClaimedAndStatusIn(
                        ehrNumber,
                        List.of(
                                VerificationRequestStatus.SUBMITTED,
                                VerificationRequestStatus.WITH_HIM,
                                VerificationRequestStatus.WITH_ICT));

        if (!alreadyOpen) {
            PatientVerificationRequest entry =
                    new PatientVerificationRequest();

            entry.setEhrNumberClaimed(ehrNumber);
            entry.setFullName(
                    request.fullName().trim());
            entry.setDateOfBirth(
                    request.dateOfBirth());
            entry.setPhoneNumber(
                    request.phoneNumber().trim());
            entry.setEmail(
                    request.email());
            entry.setPreferredContact(
                    request.preferredContact() == null
                            ? "SMS"
                            : request.preferredContact());
            entry.setSupportingNote(
                    request.supportingNote());
            entry.setIpAddress(ipAddress);

            requestRepository.save(entry);
        }

        /*
         * The same reply is returned whether a request already exists or not.
         * This prevents the help endpoint from revealing request existence.
         */
        return "Your request has been received. The hospital will contact you on the number "
                + "you provided. This usually takes one working day.";
    }

    // -----------------------------------------------------------------
    // Rate limiting
    // -----------------------------------------------------------------

    private boolean isRateLimited(
            String ehrNumber,
            String ipAddress) {

        LocalDateTime since =
                LocalDateTime.now()
                        .minusMinutes(windowMinutes);

        if (attemptRepository.countRecentFailuresForNumber(
                ehrNumber,
                since) >= maxFailuresPerNumber) {
            return true;
        }

        return ipAddress != null
                && attemptRepository.countRecentFailuresForIp(
                        ipAddress,
                        since) >= maxFailuresPerIp;
    }

    // -----------------------------------------------------------------
    // EHR number normalization
    // -----------------------------------------------------------------

    /**
     * Normalizes an EHR number without changing its actual identifier.
     *
     * We intentionally do NOT:
     * - remove punctuation,
     * - remove prefixes,
     * - remove leading zeroes,
     * - insert/remove separators.
     *
     * Only surrounding whitespace and letter case are normalized.
     */
    private String normalizeEhrNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .toUpperCase(Locale.ROOT);
    }

    private String maskEhrNumber(String value) {
        if (value == null || value.isBlank()) {
            return "null";
        }

        if (value.length() <= 4) {
            return "****" + value;
        }

        return "****"
                + value.substring(value.length() - 4);
    }

    private void record(
            String ehrNumber,
            LookupOutcome outcome,
            String ip,
            String userAgent) {

        EhrLookupAttempt attempt =
                new EhrLookupAttempt();

        String attempted =
                ehrNumber == null
                        ? ""
                        : ehrNumber;

        attempt.setEhrNumberAttempted(
                attempted.length() > 50
                        ? attempted.substring(0, 50)
                        : attempted);

        attempt.setOutcome(outcome);
        attempt.setIpAddress(ip);
        attempt.setUserAgent(userAgent);
        attempt.setAttemptedAt(
                LocalDateTime.now());

        attemptRepository.save(attempt);
    }

    public static class EnrolmentException
            extends RuntimeException {

        public EnrolmentException(String message) {
            super(message);
        }
    }
}