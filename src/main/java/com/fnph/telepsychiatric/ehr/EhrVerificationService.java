package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.ehr.api.*;
import com.fnph.telepsychiatric.email.AccountEmailService;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.session.PasswordPolicy;
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

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Patient self-enrolment against the active snapshot.
 *
 * <h2>Three steps, and none of them can be skipped</h2>
 *
 * <ol>
 *   <li><b>Lookup.</b> EHR number plus one corroborating detail. Returns masked
 *       confirmation only.</li>
 *   <li><b>Contact verification.</b> A code to a destination held in the
 *       snapshot, never to one the caller supplies.</li>
 *   <li><b>Activation.</b> The patient sets a password and the account exists.</li>
 * </ol>
 *
 * <h2>Why a corroborating detail is required</h2>
 *
 * If an EHR number alone returned a name, anyone could walk the range and
 * confirm that a named individual is a patient at a neuropsychiatric hospital.
 * That disclosure needs no account and no further step, which makes it the
 * likeliest attack on this system and the one worth engineering against.
 *
 * <h2>Why the code goes to the stored destination</h2>
 *
 * Sending it somewhere the caller supplies would mean anyone who learned an EHR
 * number and a date of birth could point the account at their own device. The
 * snapshot decides where the code goes.
 *
 * <h2>Two routes, in order of preference</h2>
 *
 * Email first, then SMS. V21 removed the SMS requirement at FNPH's direction
 * and made email the preferred route because the address comes from the
 * hospital record. A record with neither goes to the assisted queue, where a
 * coordinator reads the code to a patient standing in front of them. That is a
 * stronger check than either channel, and it is how a patient with no email and
 * no smartphone actually enrols.
 *
 * The address is stored encrypted, not in plaintext. A readable table of EHR
 * numbers with names and contact details is a directory of who is a psychiatric
 * patient here, which is what the hashes exist to prevent. It is decrypted at
 * send time and never returned to a caller.
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

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GENERIC_FAILURE =
            "We could not match those details. Check the number and the date of birth exactly "
                    + "as they appear on your hospital card, or request help below.";

    private final EhrImportRepository importRepository;
    private final EhrRecordRepository recordRepository;
    private final EhrLookupAttemptRepository attemptRepository;
    private final ContactVerificationRepository contactRepository;
    private final PatientVerificationRequestRepository requestRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SecretEncryptor secretEncryptor;
    private final AccountEmailService accountEmailService;
    private final AuditService auditService;

    @Value("${application.security.enrolment.max-lookup-failures-per-ip:10}")
    private int maxFailuresPerIp;

    @Value("${application.security.enrolment.max-lookup-failures-per-number:5}")
    private int maxFailuresPerNumber;

    @Value("${application.security.enrolment.lookup-window-minutes:30}")
    private int windowMinutes;

    @Value("${application.security.enrolment.contact-code-minutes:10}")
    private int codeMinutes;

    @Value("${application.security.enrolment.max-code-attempts:5}")
    private int maxCodeAttempts;

    // -----------------------------------------------------------------
    // Step 1: lookup
    // -----------------------------------------------------------------

    @Transactional
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

        Optional<EhrVerificationRecord> found = recordRepository
                .findByEhrImportIdAndEhrNumber(active.get().getId(), ehrNumber);

        if (found.isEmpty()) {
            record(ehrNumber, LookupOutcome.NOT_FOUND, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }
        EhrVerificationRecord snapshot = found.get();

        if (!Boolean.TRUE.equals(snapshot.getIsActiveRecord())) {
            record(ehrNumber, LookupOutcome.RECORD_INACTIVE, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (!corroborates(snapshot, request)) {
            record(ehrNumber, LookupOutcome.CORROBORATION_FAILED, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        if (patientRepository.existsByEhrNumber(ehrNumber)) {
            // Same message as every other failure. "Already enrolled" would
            // confirm both that the number is real and that this person uses
            // the service.
            record(ehrNumber, LookupOutcome.ALREADY_ENROLLED, ipAddress, userAgent);
            throw new EnrolmentException(GENERIC_FAILURE);
        }

        // Route, in V21's stated order of preference. Neither branch lets the
        // caller nominate a destination.
        ContactChannel channel;
        String destinationMasked;
        String deliveryRoute;
        String sendTo = null;

        if (snapshot.getEmailEncrypted() != null) {
            channel = ContactChannel.EMAIL;
            destinationMasked = snapshot.getEmailMasked();
            deliveryRoute = "EMAIL";
            sendTo = secretEncryptor.decrypt(snapshot.getEmailEncrypted());
        } else if (snapshot.getPhoneHash() != null) {
            channel = ContactChannel.SMS;
            destinationMasked = snapshot.getPhoneMasked();
            deliveryRoute = "SMS";
        } else {
            // Nothing on file. The assisted queue is the answer rather than a
            // destination the caller supplies. A patient enrolling remotely
            // with no contact details cannot self-enrol, and that is correct:
            // there is no way to prove the device is theirs.
            throw new EnrolmentException(
                    "We have no contact details on file for this record. Please request help "
                            + "below so the hospital can verify you directly.");
        }

        record(ehrNumber, LookupOutcome.MATCHED, ipAddress, userAgent);

        contactRepository.invalidateOutstanding(ehrNumber, LocalDateTime.now());
        String code = sixDigitCode();

        ContactVerification verification = new ContactVerification();
        verification.setEhrNumber(ehrNumber);
        verification.setChannel(channel);
        // Set explicitly. V21 added this column so a support call can be
        // answered with where the code actually went. It defaulted to "EMAIL"
        // and nothing ever wrote it, so every row claimed email regardless of
        // what happened.
        verification.setDeliveryRoute(deliveryRoute);
        verification.setDestinationMasked(destinationMasked);
        verification.setCodeHash(Tokens.hash(code));
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(codeMinutes));
        verification.setIpAddress(ipAddress);
        // Carried so activation does not have to invent one. See activate().
        verification.setCorroboratedDateOfBirth(request.dateOfBirth());
        ContactVerification saved = contactRepository.save(verification);

        if (channel == ContactChannel.EMAIL) {
            accountEmailService.sendEnrolmentCode(
                    sendTo, snapshot.getFullName(), code, verification.getExpiresAt());
        } else {
            // TODO(notifications): dispatch by SMS once a provider is chosen.
            // Must not survive into production; a code in a log is a code
            // anyone with log access can use.
            log.info("Enrolment code for {} (send to {}): {}",
                    ehrNumber, destinationMasked, code);
        }

        return new EnrolmentLookupResponse(
                saved.getPublicId(),
                snapshot.getFullName(),
                snapshot.getDateOfBirthMasked(),
                destinationMasked,
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
    private boolean corroborates(EhrVerificationRecord snapshot, EnrolmentLookupRequest request) {
        if (request.dateOfBirth() != null) {
            return Tokens.hash(request.dateOfBirth().toString())
                    .equals(snapshot.getDateOfBirthHash());
        }
        if (request.phoneLastFour() != null && snapshot.getPhoneMasked() != null) {
            String stored = snapshot.getPhoneMasked();
            return stored.endsWith(request.phoneLastFour().trim());
        }
        return false;
    }

    // -----------------------------------------------------------------
    // Steps 2 and 3: verify the code, then activate
    // -----------------------------------------------------------------

    @Transactional
    public void activate(CompleteEnrolmentRequest request, String ipAddress) {
        ContactVerification verification = contactRepository
                .findByPublicId(request.verificationPublicId())
                .filter(v -> v.isUsable(LocalDateTime.now()))
                .orElseThrow(() -> new EnrolmentException(
                        "That code has expired. Start again to receive a new one."));

        if (verification.getAttempts() >= maxCodeAttempts) {
            verification.setInvalidatedAt(LocalDateTime.now());
            contactRepository.save(verification);
            throw new EnrolmentException("Too many incorrect codes. Start again.");
        }

        if (!Tokens.matches(request.code().trim(), verification.getCodeHash())) {
            verification.setAttempts(verification.getAttempts() + 1);
            contactRepository.save(verification);
            throw new EnrolmentException("That code is not correct.");
        }

        EhrVerificationImport active = importRepository.findFirstByStatus(ImportStatus.ACTIVE)
                .orElseThrow(() -> new EnrolmentException("Enrolment is temporarily unavailable."));

        EhrVerificationRecord snapshot = recordRepository
                .findByEhrImportIdAndEhrNumber(active.getId(), verification.getEhrNumber())
                .orElseThrow(() -> new EnrolmentException(
                        "That record is no longer available. Request help below."));

        if (patientRepository.existsByEhrNumber(snapshot.getEhrNumber())) {
            throw new EnrolmentException("That record is already enrolled.");
        }

        List<String> problems = passwordPolicy.validate(
                request.password(), snapshot.getEhrNumber(), null, snapshot.getFullName());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }

        LocalDateTime now = LocalDateTime.now();
        String[] nameParts = snapshot.getFullName().trim().split("\\s+", 2);

        Patient patient = new Patient();
        patient.setEhrNumber(snapshot.getEhrNumber());
        patient.setFirstName(nameParts[0]);
        patient.setLastName(nameParts.length > 1 ? nameParts[1] : nameParts[0]);
        patient.setDateOfBirth(dateOfBirthFor(verification, snapshot));
        patient.setSourceImportId(active.getId());
        patient.setIsEligible(true);
        patient.setIsPhysicallyAssessed(true);
        patient.setEligibilityVerifiedAt(now);
        patient.setEligibilityVerifiedBy("EHR snapshot dated " + active.getSourceAsAt());
        patient.setContactVerifiedAt(now);
        patient.setActivatedAt(now);
        patient.setIsActive(true);
        Patient savedPatient = patientRepository.save(patient);

        Role patientRole = roleRepository.findByCode("PATIENT")
                .orElseThrow(() -> new IllegalStateException("PATIENT role is not seeded"));

        Users account = new Users();
        // The EHR number becomes the username, which is how all three login
        // types converge on one lookup.
        account.setUsername(snapshot.getEhrNumber().toLowerCase());
        account.setEmail(snapshot.getEhrNumber().toLowerCase() + "@patient.fnph.local");
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
            entry.setPhoneNumber(request.phoneNumber().trim());
            entry.setEmail(request.email());
            entry.setPreferredContact(request.preferredContact() == null
                    ? "SMS" : request.preferredContact());
            entry.setSupportingNote(request.supportingNote());
            entry.setIpAddress(ipAddress);
            requestRepository.save(entry);
        }

        // The same reply either way. A different response for a duplicate would
        // reveal that a request already exists for that number.
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

    private String sixDigitCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public static class EnrolmentException extends RuntimeException {
        public EnrolmentException(String message) {
            super(message);
        }
    }
}