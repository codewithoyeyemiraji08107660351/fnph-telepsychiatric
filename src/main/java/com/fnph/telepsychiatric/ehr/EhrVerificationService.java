package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.ehr.api.CompleteEnrolmentRequest;
import com.fnph.telepsychiatric.ehr.api.EnrolmentLookupRequest;
import com.fnph.telepsychiatric.ehr.api.EnrolmentLookupResponse;
import com.fnph.telepsychiatric.ehr.api.VerificationHelpRequest;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.Role;
import com.fnph.telepsychiatric.user.RoleRepository;
import com.fnph.telepsychiatric.user.UserRole;
import com.fnph.telepsychiatric.user.UserRoleRepository;
import com.fnph.telepsychiatric.user.Users;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.LoginType;
import com.fnph.telepsychiatric.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EhrVerificationService {

    private static final String GENERIC_FAILURE =
            "We could not match that hospital number. " +
            "Check it exactly as it appears on your hospital card, " +
            "or request help below.";

    private final EhrImportRepository importRepository;
    private final ManualEhrRecordService manualEhrRecordService;
    private final EhrLookupAttemptRepository lookupAttemptRepository;
    private final ContactVerificationRepository contactRepository;
    private final PatientVerificationRequestRepository verificationRequestRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
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

    @Transactional(noRollbackFor = EnrolmentException.class)
    public EnrolmentLookupResponse lookup(
            EnrolmentLookupRequest request,
            String ipAddress,
            String userAgent
    ) {
        String ehrNumber = normalizeEhrNumber(
                request.ehrNumber()
        );

        log.info(
                "EHR self-enrolment lookup received for number='{}'",
                maskEhrNumber(ehrNumber)
        );

        if (ehrNumber == null) {
            record(
                    null,
                    LookupOutcome.NOT_FOUND,
                    ipAddress,
                    userAgent
            );

            throw new EnrolmentException(
                    GENERIC_FAILURE
            );
        }

        if (isRateLimited(ehrNumber, ipAddress)) {
            record(
                    ehrNumber,
                    LookupOutcome.RATE_LIMITED,
                    ipAddress,
                    userAgent
            );

            throw new EnrolmentException(
                    "Too many attempts. Wait 30 minutes, " +
                    "or request help below."
            );
        }

        Optional<EhrVerificationImport> active =
                importRepository.findFirstByStatus(
                        ImportStatus.ACTIVE
                );

        if (active.isEmpty()) {
            record(
                    ehrNumber,
                    LookupOutcome.NO_ACTIVE_IMPORT,
                    ipAddress,
                    userAgent
            );

            log.error(
                    "Enrolment attempted with no active EHR snapshot. " +
                    "Patients cannot enrol."
            );

            throw new EnrolmentException(
                    "Enrolment is temporarily unavailable. " +
                    "Please try again later."
            );
        }

        EhrVerificationImport activeImport =
                active.get();

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        activeImport.getId(),
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
                    activeImport.getId()
            );

            throw new EnrolmentException(
                    GENERIC_FAILURE
            );
        }

        /*
         * EHR NUMBER IS THE ONLY VERIFICATION FACTOR.
         *
         * DOB, phone number and other contact details are deliberately
         * not checked here.
         */
        if (!Boolean.TRUE.equals(
                snapshot.getIsActiveRecord()
        )) {
            record(
                    ehrNumber,
                    LookupOutcome.RECORD_INACTIVE,
                    ipAddress,
                    userAgent
            );

            throw new EnrolmentException(
                    GENERIC_FAILURE
            );
        }

        /*
         * Do not allow duplicate patient enrolment.
         */
        if (patientRepository.existsByEhrNumber(ehrNumber)) {
            record(
                    ehrNumber,
                    LookupOutcome.ALREADY_ENROLLED,
                    ipAddress,
                    userAgent
            );

            throw new EnrolmentException(
                    "This hospital record already has an account. " +
                    "Sign in with your EHR number and password, " +
                    "or reset your password."
            );
        }

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

        ContactVerification verification =
                new ContactVerification();

        verification.setEhrNumber(ehrNumber);

        /*
         * No DOB or phone corroboration is required.
         */
        verification.setChannel(
                ContactChannel.EMAIL
        );

        verification.setDeliveryRoute(
                "SELF_SERVICE"
        );

        verification.setDestinationMasked(
                "Not required"
        );

        /*
         * This token is used to bind the lookup to the subsequent
         * completion request. It is not a second EHR verification
         * factor.
         */
        String setupToken =
                Tokens.generate();

        verification.setCodeHash(
                Tokens.hash(setupToken)
        );

        verification.setExpiresAt(
                LocalDateTime.now()
                        .plusMinutes(codeMinutes)
        );

        verification.setIpAddress(
                ipAddress
        );

        /*
         * Important:
         * Do NOT store a user-supplied DOB as corroboration.
         */
        verification.setCorroboratedDateOfBirth(
                null
        );

        ContactVerification saved =
                contactRepository.save(
                        verification
                );

        return new EnrolmentLookupResponse(
                saved.getPublicId(),
                snapshot.getEhrNumber(),
                snapshot.getFullName(),
                snapshot.getDateOfBirthMasked(),
                snapshot.getClinic(),
                verification.getExpiresAt(),
                activeImport.getSourceAsAt(),
                activeImport.ageInDays()
        );
    }

    @Transactional(noRollbackFor = EnrolmentException.class)
    public void activate(
            CompleteEnrolmentRequest request,
            String ipAddress
    ) {
        ContactVerification verification =
                contactRepository
                        .findByPublicId(
                                request.verificationPublicId()
                        )
                        .filter(v ->
                                v.isUsable(
                                        LocalDateTime.now()
                                )
                        )
                        .orElseThrow(() ->
                                new EnrolmentException(
                                        "That setup session has expired. " +
                                        "Start again."
                                )
                        );

        EhrVerificationImport active =
                importRepository
                        .findFirstByStatus(
                                ImportStatus.ACTIVE
                        )
                        .orElseThrow(() ->
                                new EnrolmentException(
                                        "Enrolment is temporarily unavailable."
                                )
                        );

        String ehrNumber =
                normalizeEhrNumber(
                        verification.getEhrNumber()
                );

        EhrVerificationRecord snapshot =
                manualEhrRecordService.effectiveRecord(
                        active.getId(),
                        ehrNumber
                );

        if (snapshot == null) {
            throw new EnrolmentException(
                    "That record is no longer available. " +
                    "Request help below."
            );
        }

        if (!Boolean.TRUE.equals(
                snapshot.getIsActiveRecord()
        )) {
            throw new EnrolmentException(
                    "That record is no longer active. " +
                    "Request help below."
            );
        }

        if (patientRepository.existsByEhrNumber(
                ehrNumber
        )) {
            throw new EnrolmentException(
                    "That record is already enrolled."
            );
        }

        if (request.password() == null ||
                request.password().length() < 8) {
            throw new IllegalArgumentException(
                    "Use at least 8 characters"
            );
        }

        LocalDateTime now =
                LocalDateTime.now();

        String fullName =
                snapshot.getFullName() == null
                        ? ""
                        : snapshot.getFullName()
                                .trim()
                                .replaceAll("\\s+", " ");

        String[] nameParts =
                fullName.split("\\s+", 2);

        String firstName =
                nameParts.length > 0 &&
                !nameParts[0].isBlank()
                        ? nameParts[0]
                        : "Patient";

        String lastName =
                nameParts.length > 1 &&
                !nameParts[1].isBlank()
                        ? nameParts[1]
                        : firstName;

        Patient patient =
                new Patient();

        patient.setEhrNumber(
                ehrNumber
        );

        patient.setFirstName(
                firstName
        );

        patient.setLastName(
                lastName
        );

        /*
         * EHR number is the verification factor.
         * Patient DOB is populated from the authoritative snapshot,
         * not from user corroboration.
         */
        patient.setDateOfBirth(
                dateOfBirthFromSnapshot(snapshot)
        );

        if (snapshot.getEmailEncrypted() != null) {
            patient.setEmail(
                    secretEncryptor.decrypt(
                            snapshot.getEmailEncrypted()
                    )
            );
        }

        patient.setSourceImportId(
                active.getId()
        );

        patient.setIsEligible(
                true
        );

        patient.setIsPhysicallyAssessed(
                true
        );

        patient.setEligibilityVerifiedAt(
                now
        );

        patient.setEligibilityVerifiedBy(
                "EHR snapshot dated " +
                active.getSourceAsAt()
        );

        patient.setActivatedAt(
                now
        );

        patient.setIsActive(
                true
        );

        Patient savedPatient =
                patientRepository.save(
                        patient
                );

        Role patientRole =
                roleRepository
                        .findByCode("PATIENT")
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "PATIENT role is not seeded"
                                )
                        );

        Users account =
                new Users();

        String username =
                ehrNumber.toLowerCase(
                        Locale.ROOT
                );

        account.setUsername(
                username
        );

        account.setEmail(
                username +
                "@patient.fnph.local"
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

        account.setIsActive(
                true
        );

        account.setMfaEnabled(
                false
        );

        account.setActivatedAt(
                now
        );

        account.setPasswordChangedAt(
                now
        );

        Users savedAccount =
                userRepository.save(
                        account
                );

        UserRole assignment =
                new UserRole();

        assignment.setUserId(
                savedAccount.getId()
        );

        assignment.setRoleId(
                patientRole.getId()
        );

        assignment.setIsPrimary(
                true
        );

        assignment.setGrantedAt(
                now
        );

        assignment.setGrantedBy(
                "system"
        );

        assignment.setGrantReason(
                "Self-enrollment against EHR snapshot " +
                active.getPublicId()
        );

        userRoleRepository.save(
                assignment
        );

        verification.setVerifiedAt(
                now
        );

        verification.setPatient(
                savedPatient
        );

        /*
         * Explicitly no corroborated DOB.
         */
        verification.setCorroboratedDateOfBirth(
                null
        );

        contactRepository.save(
                verification
        );

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(
                                AuditAction.USER_ACTIVATED
                        )
                        .entityType(
                                "Patient"
                        )
                        .entityId(
                                savedPatient.getId()
                        )
                        .details(
                                "Patient self-enrolled using " +
                                "active EHR snapshot " +
                                active.getPublicId()
                        )
                        .reason(
                                "EHR-number-only self-enrolment"
                        )
                        .build()
        );
    }

    private LocalDate dateOfBirthFromSnapshot(
            EhrVerificationRecord snapshot
    ) {
        if (snapshot.getDateOfBirthEncrypted() == null) {
            /*
             * Legacy snapshots may only contain a masked year.
             *
             * Preserve the previous safe fallback rather than
             * failing an otherwise valid EHR-number verification.
             */
            String masked =
                    snapshot.getDateOfBirthMasked();

            if (masked != null &&
                    masked.length() >= 4) {

                try {
                    int year =
                            Integer.parseInt(
                                    masked.substring(
                                            masked.length() - 4
                                    )
                            );

                    if (year >= 1900 &&
                            year <= LocalDate.now().getYear()) {
                        return LocalDate.of(
                                year,
                                1,
                                1
                        );
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to null.
                }
            }

            return null;
        }

        String decrypted =
                secretEncryptor.decrypt(
                        snapshot.getDateOfBirthEncrypted()
                );

        if (decrypted == null ||
                decrypted.isBlank()) {
            return null;
        }

        return LocalDate.parse(
                decrypted
        );
    }

    private boolean isRateLimited(
            String ehrNumber,
            String ipAddress
    ) {
        LocalDateTime since =
                LocalDateTime.now()
                        .minusMinutes(
                                windowMinutes
                        );

        long numberFailures =
                lookupAttemptRepository
                        .countRecentFailuresForNumber(
                                ehrNumber,
                                since
                        );

        if (numberFailures >=
                maxFailuresPerNumber) {
            return true;
        }

        long ipFailures =
                lookupAttemptRepository
                        .countRecentFailuresForIp(
                                ipAddress,
                                since
                        );

        return ipFailures >=
                maxFailuresPerIp;
    }

    private void record(
            String ehrNumber,
            LookupOutcome outcome,
            String ipAddress,
            String userAgent
    ) {
        EhrLookupAttempt attempt =
                new EhrLookupAttempt();

        attempt.setEhrNumber(
                ehrNumber
        );

        attempt.setOutcome(
                outcome
        );

        attempt.setIpAddress(
                ipAddress
        );

        attempt.setUserAgent(
                userAgent
        );

        attempt.setAttemptedAt(
                LocalDateTime.now()
        );

        lookupAttemptRepository.save(
                attempt
        );
    }

    private String normalizeEhrNumber(
            String value
    ) {
        if (value == null ||
                value.isBlank()) {
            return null;
        }

        return value
                .trim()
                .toUpperCase(
                        Locale.ROOT
                );
    }

    private String maskEhrNumber(
            String value
    ) {
        if (value == null ||
                value.isBlank()) {
            return "null";
        }

        int length =
                value.length();

        if (length <= 4) {
            return "****" + value;
        }

        return "****" +
                value.substring(
                        length - 4
                );
    }

    public String requestVerification(
            VerificationHelpRequest request,
            String ipAddress
    ) {
        /*
         * Keep your existing help/manual-verification implementation
         * here unchanged.
         *
         * This method is intentionally not part of the EHR lookup
         * matching logic.
         */
        throw new UnsupportedOperationException(
                "Use the existing requestVerification implementation."
        );
    }

    private enum LookupOutcome {
        MATCHED,
        NOT_FOUND,
        RECORD_INACTIVE,
        ALREADY_ENROLLED,
        RATE_LIMITED,
        NO_ACTIVE_IMPORT
    }

    public static class EnrolmentException
            extends RuntimeException {

        public EnrolmentException(
                String message
        ) {
            super(message);
        }
    }
}

