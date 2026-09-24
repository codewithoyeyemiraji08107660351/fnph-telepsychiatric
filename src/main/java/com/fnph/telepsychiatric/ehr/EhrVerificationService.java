package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.ehr.api.CompleteEnrolmentRequest;
import com.fnph.telepsychiatric.ehr.api.EnrolmentLookupRequest;
import com.fnph.telepsychiatric.ehr.api.EnrolmentLookupResponse;
import com.fnph.telepsychiatric.ehr.api.VerificationHelpRequest;
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
 * Patient self-enrolment against the active EHR snapshot.
 *
 * <h2>Two short steps</h2>
 *
 * <ol>
 *     <li>Lookup the supplied EHR number against the active snapshot.</li>
 *     <li>The patient chooses a password and the account is activated.</li>
 * </ol>
 *
 * <h2>Verification rule</h2>
 *
 * The EHR number is the sole lookup credential.
 *
 * A patient is considered eligible for self-enrolment when:
 *
 * <ul>
 *     <li>an active EHR snapshot exists;</li>
 *     <li>the supplied EHR number exists in that snapshot; and</li>
 *     <li>the EHR record is marked active.</li>
 * </ul>
 *
 * Date of birth, phone number, email address and other EHR fields are NOT
 * used as additional verification factors during self-enrolment.
 *
 * The EHR snapshot remains authoritative for the patient's identity and
 * demographic information used when the account is subsequently created.
 *
 * <h2>Privacy</h2>
 *
 * Failed lookup results intentionally use a generic response so the public
 * enrolment form does not become an unrestricted EHR-number enumeration tool.
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
    // Step 1: EHR lookup
    // -----------------------------------------------------------------

    /**
     * Looks up an EHR number against the active EHR snapshot.
     *
     * Verification is based ONLY on:
     *
     * 1. EHR number exists in the active snapshot.
     * 2. EHR record is active.
     * 3. EHR number has not already been enrolled.
     *
     * DOB, phone number and other corroborating information are deliberately
     * not checked.
     */
    @Transactional(noRollbackFor = EnrolmentException.class)
    public EnrolmentLookupResponse lookup(
            EnrolmentLookupRequest request,
            String ipAddress,
            String userAgent) {

        if (request == null || request.ehrNumber() == null
                || request.ehrNumber().isBlank()) {

            throw new EnrolmentException(GENERIC_FAILURE);
        }

        String ehrNumber = request.ehrNumber().trim();

        log.info(
                "EHR self-enrolment lookup received for number='{}'",
                maskEhrNumber(ehrNumber)
        );

        // -------------------------------------------------------------
        // Rate limiting
        // -------------------------------------------------------------

        if (isRateLimited(ehrNumber, ipAddress)) {

            record(
                    ehrNumber,
                    LookupOutcome.RATE_LIMITED,
                    ipAddress,
                    userAgent
            );

            throw new EnrolmentException(
                    "Too many attempts. Wait 30 minutes, or request help below."
            );
        }

        // -------------------------------------------------------------
        // Find active EHR snapshot
        // -------------------------------------------------------------

        Optional<EhrVerificationImport> active =
                importRepository.findFirstByStatus(ImportStatus.ACTIVE);

        if (active.isEmpty()) {

            record(
                    ehrNumber,
                    LookupOutcome.NO_ACTIVE_IMPORT,
                    ipAddress,
                    userAgent
            );

            log.error(
                    "EHR self-enrolment attempted with no active EHR snapshot"
            );

            throw new EnrolmentException(
                    "Enrolment is temporarily unavailable. Please try again later."
            );
        }

        // -------------------------------------------------------------
        // Find EHR record
        // -------------------------------------------------------------

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        active.get().getId(),
                        ehrNumber
                );

        if (snapshot == null) {

            record(
                    ehrNumber,
                    LookupOutcome.NOT_FOUND,
                    ipAddress,
                    userAgent
            );

            log.info(
                    "EHR lookup did not match snapshot: number='{}', importId={}",
                    maskEhrNumber(ehrNumber),
                    active.get().getId()
            );

            throw new EnrolmentException(GENERIC_FAILURE);
        }

        // -------------------------------------------------------------
        // EHR record must be active
        // -------------------------------------------------------------

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {

            record(
                    ehrNumber,
                    LookupOutcome.RECORD_INACTIVE,
                    ipAddress,
                    userAgent
            );

            log.info(
                    "EHR record is inactive: number='{}'",
                    maskEhrNumber(ehrNumber)
            );

            throw new EnrolmentException(GENERIC_FAILURE);
        }

        // -------------------------------------------------------------
        // Prevent duplicate patient enrolment
        // -------------------------------------------------------------

        if (patientRepository.existsByEhrNumber(ehrNumber)) {

            record(
                    ehrNumber,
                    LookupOutcome.ALREADY_ENROLLED,
                    ipAddress,
                    userAgent
            );

            log.info(
                    "EHR number already enrolled: number='{}'",
                    maskEhrNumber(ehrNumber)
            );

            // Keep the public response generic so the endpoint does not
            // reveal whether an EHR number belongs to an existing account.
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        // -------------------------------------------------------------
        // Successful match
        // -------------------------------------------------------------

        record(
                ehrNumber,
                LookupOutcome.MATCHED,
                ipAddress,
                userAgent
        );

        contactRepository.invalidateOutstanding(
                ehrNumber,
                LocalDateTime.now()
        );

        /*
         * Reuse the short-lived verification table as the enrolment setup
         * session.
         *
         * No email/SMS/contact verification is performed here.
         *
         * The generated token is stored hashed and returned through the
         * response as the short-lived setup reference.
         */
        ContactVerification verification = new ContactVerification();

        verification.setEhrNumber(ehrNumber);
        verification.setChannel(ContactChannel.EMAIL);
        verification.setDeliveryRoute("SELF_SERVICE");
        verification.setDestinationMasked("Not required");

        String setupToken = Tokens.generate();

        verification.setCodeHash(
                Tokens.hash(setupToken)
        );

        verification.setExpiresAt(
                LocalDateTime.now().plusMinutes(codeMinutes)
        );

        verification.setIpAddress(ipAddress);

        /*
         * No DOB corroboration is performed.
         */
        verification.setCorroboratedDateOfBirth(null);

        ContactVerification saved =
                contactRepository.save(verification);

        log.info(
                "EHR lookup matched active record: number='{}', importId={}, setupSession={}",
                maskEhrNumber(ehrNumber),
                active.get().getId(),
                saved.getPublicId()
        );

        return new EnrolmentLookupResponse(
                saved.getPublicId(),
                snapshot.getEhrNumber(),
                snapshot.getFullName(),
                snapshot.getDateOfBirthMasked(),
                snapshot.getClinic(),
                verification.getExpiresAt(),
                active.get().getSourceAsAt(),
                active.get().ageInDays()
        );
    }

    // -----------------------------------------------------------------
    // Step 2: choose password and activate account
    // -----------------------------------------------------------------

    @Transactional(noRollbackFor = EnrolmentException.class)
    public void activate(
            CompleteEnrolmentRequest request,
            String ipAddress) {

        ContactVerification verification =
                contactRepository
                        .findByPublicId(request.verificationPublicId())
                        .filter(v -> v.isUsable(LocalDateTime.now()))
                        .orElseThrow(() ->
                                new EnrolmentException(
                                        "That setup session has expired. Start again."
                                )
                        );

        EhrVerificationImport active =
                importRepository
                        .findFirstByStatus(ImportStatus.ACTIVE)
                        .orElseThrow(() ->
                                new EnrolmentException(
                                        "Enrolment is temporarily unavailable."
                                )
                        );

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        active.getId(),
                        verification.getEhrNumber()
                );

        if (snapshot == null) {
            throw new EnrolmentException(
                    "That record is no longer available. Request help below."
            );
        }

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {
            throw new EnrolmentException(
                    "That record is no longer active. Request help below."
            );
        }

        if (patientRepository.existsByEhrNumber(snapshot.getEhrNumber())) {
            throw new EnrolmentException(
                    "That record is already enrolled."
            );
        }

        // -------------------------------------------------------------
        // Password validation
        // -------------------------------------------------------------

        if (request.password() == null || request.password().length() < 8) {
            throw new EnrolmentException(
                    "Use at least 8 characters for your password."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        // -------------------------------------------------------------
        // Build patient from EHR snapshot
        // -------------------------------------------------------------

        String fullName = snapshot.getFullName() == null
                ? ""
                : snapshot.getFullName().trim();

        if (fullName.isBlank()) {
            throw new EnrolmentException(
                    "The hospital record is missing the patient's name. Request help below."
            );
        }

        String[] nameParts =
                fullName.split("\\s+", 2);

        Patient patient = new Patient();

        patient.setEhrNumber(
                snapshot.getEhrNumber().trim()
        );

        patient.setFirstName(
                nameParts[0]
        );

        patient.setLastName(
                nameParts.length > 1
                        ? nameParts[1]
                        : nameParts[0]
        );

        /*
         * Use the real encrypted DOB if available in the snapshot.
         *
         * If the snapshot only contains a masked DOB, use the existing
         * fallback behaviour. This is NOT used for verification.
         */
        patient.setDateOfBirth(
                dateOfBirthFor(snapshot)
        );

        // -------------------------------------------------------------
        // Preserve hospital email if available
        // -------------------------------------------------------------

        if (snapshot.getEmailEncrypted() != null
                && !snapshot.getEmailEncrypted().isBlank()) {

            try {
                patient.setEmail(
                        secretEncryptor.decrypt(
                                snapshot.getEmailEncrypted()
                        )
                );
            } catch (RuntimeException e) {

                log.warn(
                        "Could not decrypt EHR email for number='{}': {}",
                        maskEhrNumber(snapshot.getEhrNumber()),
                        e.getMessage()
                );
            }
        }

        patient.setSourceImportId(
                active.getId()
        );

        patient.setIsEligible(true);
        patient.setIsPhysicallyAssessed(true);
        patient.setEligibilityVerifiedAt(now);

        patient.setEligibilityVerifiedBy(
                "EHR snapshot dated " + active.getSourceAsAt()
        );

        patient.setActivatedAt(now);
        patient.setIsActive(true);

        Patient savedPatient =
                patientRepository.save(patient);

        // -------------------------------------------------------------
        // Patient role
        // -------------------------------------------------------------

        Role patientRole =
                roleRepository
                        .findByCode("PATIENT")
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "PATIENT role is not seeded"
                                )
                        );

        // -------------------------------------------------------------
        // Create user account
        // -------------------------------------------------------------

        Users account = new Users();

        String username =
                snapshot.getEhrNumber()
                        .trim()
                        .toLowerCase();

        account.setUsername(username);

        /*
         * Internal email only.
         *
         * The EHR email remains on the patient record and is not used as
         * the login identifier.
         */
        account.setEmail(
                username + "@patient.fnph.local"
        );

        account.setPassword(
                passwordEncoder.encode(
                        request.password()
                )
        );

        account.setFirstName(
                patient.getFirstName()
        );

        account.setLastName(
                patient.getLastName()
        );

        account.setPatient(
                savedPatient
        );

        account.setLoginType(
                LoginType.EHR_NUMBER
        );

        account.setStatus(
                UserStatus.ACTIVE
        );

        account.setIsActive(true);

        /*
         * Patient self-enrolment does not require MFA.
         */
        account.setMfaEnabled(false);

        account.setActivatedAt(now);
        account.setPasswordChangedAt(now);

        Users savedAccount =
                userRepository.save(account);

        // -------------------------------------------------------------
        // Assign PATIENT role
        // -------------------------------------------------------------

        UserRole assignment =
                new UserRole();

        assignment.setUserId(
                savedAccount.getId()
        );

        assignment.setRoleId(
                patientRole.getId()
        );

        assignment.setIsPrimary(true);
        assignment.setGrantedAt(now);
        assignment.setGrantedBy("system");

        assignment.setGrantReason(
                "Self-enrolled against EHR snapshot "
                        + active.getPublicId()
        );

        userRoleRepository.save(assignment);

        // -------------------------------------------------------------
        // Complete setup session
        // -------------------------------------------------------------

        verification.setVerifiedAt(now);
        verification.setPatient(savedPatient);

        contactRepository.save(
                verification
        );

        // -------------------------------------------------------------
        // Audit
        // -------------------------------------------------------------

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(AuditAction.USER_ACTIVATED)
                        .entityType("Patient")
                        .entityId(savedPatient.getId())
                        .details(
                                "Self-enrolled against snapshot dated "
                                        + active.getSourceAsAt()
                        )
                        .ipAddress(ipAddress)
                        .build()
        );

        log.info(
                "Patient {} enrolled successfully against snapshot {}",
                savedPatient.getPublicId(),
                active.getPublicId()
        );
    }

    // -----------------------------------------------------------------
    // Patient date of birth
    // -----------------------------------------------------------------

    /**
     * Retrieves the actual DOB from the encrypted snapshot where available.
     *
     * This method is used only to populate the patient's profile after
     * successful EHR-number verification.
     *
     * It is NOT used to decide whether the patient may enrol.
     */
    private LocalDate dateOfBirthFor(
            EhrVerificationRecord snapshot) {

        if (snapshot.getDateOfBirthEncrypted() != null
                && !snapshot.getDateOfBirthEncrypted().isBlank()) {

            try {
                return LocalDate.parse(
                        secretEncryptor.decrypt(
                                snapshot.getDateOfBirthEncrypted()
                        )
                );
            } catch (RuntimeException e) {

                log.warn(
                        "Could not read stored DOB for number='{}': {}",
                        maskEhrNumber(snapshot.getEhrNumber()),
                        e.getMessage()
                );
            }
        }

        /*
         * The snapshot only has a masked DOB.
         *
         * Keep the existing placeholder behaviour rather than treating the
         * masked value as a verified exact DOB.
         */
        String maskedDob = snapshot.getDateOfBirthMasked();

        if (maskedDob == null || maskedDob.length() < 4) {
            return null;
        }

        try {

            String yearPart =
                    maskedDob.substring(
                            maskedDob.length() - 4
                    );

            int year =
                    Integer.parseInt(yearPart);

            log.warn(
                    "Patient {} enrolled with masked DOB only. "
                            + "Recording 1 January {} as placeholder; "
                            + "this is NOT the verified clinical DOB.",
                    maskEhrNumber(snapshot.getEhrNumber()),
                    year
            );

            return LocalDate.of(
                    year,
                    1,
                    1
            );

        } catch (RuntimeException e) {

            log.warn(
                    "Unable to derive DOB year from masked EHR DOB for number='{}'",
                    maskEhrNumber(snapshot.getEhrNumber())
            );

            return null;
        }
    }

    // -----------------------------------------------------------------
    // Help / manual verification queue
    // -----------------------------------------------------------------

    @Transactional
    public String requestVerification(
            VerificationHelpRequest request,
            String ipAddress) {

        boolean alreadyOpen =
                requestRepository.existsByEhrNumberClaimedAndStatusIn(
                        request.ehrNumber().trim(),
                        List.of(
                                VerificationRequestStatus.SUBMITTED,
                                VerificationRequestStatus.WITH_HIM,
                                VerificationRequestStatus.WITH_ICT
                        )
                );

        if (!alreadyOpen) {

            PatientVerificationRequest entry =
                    new PatientVerificationRequest();

            entry.setEhrNumberClaimed(
                    request.ehrNumber().trim()
            );

            entry.setFullName(
                    request.fullName().trim()
            );

            entry.setDateOfBirth(
                    request.dateOfBirth()
            );

            entry.setPhoneNumber(
                    request.phoneNumber() == null
                            ? null
                            : request.phoneNumber().trim()
            );

            entry.setEmail(
                    request.email()
            );

            entry.setPreferredContact(
                    request.preferredContact() == null
                            ? "SMS"
                            : request.preferredContact()
            );

            entry.setSupportingNote(
                    request.supportingNote()
            );

            entry.setIpAddress(
                    ipAddress
            );

            requestRepository.save(entry);
        }

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
                since
        ) >= maxFailuresPerNumber) {

            return true;
        }

        return ipAddress != null
                && attemptRepository.countRecentFailuresForIp(
                        ipAddress,
                        since
                ) >= maxFailuresPerIp;
    }

    // -----------------------------------------------------------------
    // Audit lookup attempts
    // -----------------------------------------------------------------

    private void record(
            String ehrNumber,
            LookupOutcome outcome,
            String ip,
            String userAgent) {

        EhrLookupAttempt attempt =
                new EhrLookupAttempt();

        attempt.setEhrNumberAttempted(
                ehrNumber.length() > 50
                        ? ehrNumber.substring(0, 50)
                        : ehrNumber
        );

        attempt.setOutcome(
                outcome
        );

        attempt.setIpAddress(
                ip
        );

        attempt.setUserAgent(
                userAgent
        );

        attempt.setAttemptedAt(
                LocalDateTime.now()
        );

        attemptRepository.save(
                attempt
        );
    }

    // -----------------------------------------------------------------
    // Logging helper
    // -----------------------------------------------------------------

    private String maskEhrNumber(String ehrNumber) {

        if (ehrNumber == null || ehrNumber.isBlank()) {
            return "***";
        }

        String value = ehrNumber.trim();

        if (value.length() <= 4) {
            return "****";
        }

        return "****"
                + value.substring(value.length() - 4);
    }

    // -----------------------------------------------------------------
    // Exception
    // -----------------------------------------------------------------

    public static class EnrolmentException
            extends RuntimeException {

        public EnrolmentException(String message) {
            super(message);
        }
    }
}
