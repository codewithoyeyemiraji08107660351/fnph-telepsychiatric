package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.common.BusinessRuleException;
import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CentreStatus;
import com.fnph.telepsychiatric.clinical.ReleaseBundle;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fnph.telepsychiatric.tenancy.TenantContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Referrals, and the centre's incoming queue.
 *
 * <h2>A centre patient is never an FNPH patient</h2>
 *
 * Even when the referral text mentions an FNPH EHR number, the offline record
 * is not linked or retrieved. Nothing in this service reads it, and the tenant
 * filter means a centre cannot reach an FNPH patient record by any route.
 *
 * <h2>Consent belongs to the referral</h2>
 *
 * Recorded per request rather than per patient. A patient consented to a
 * consultation in March; that is not consent to one in September, and a system
 * that treats it as one has stopped asking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreReferralService {

    private final CentreReferralRepository referralRepository;
    private final CentreBundleReceiptRepository receiptRepository;
    private final CentreBundleActionRepository actionRepository;
    private final CentreWalletService walletService;
    private final InAppNotificationService notifications;
    private final AuditService auditService;
    private final com.fnph.telepsychiatric.triage.TriageService triageService;

    @Transactional
    public CentreReferral create(Center centre, CentrePatient patient, ReferralDetails details) {
        if (centre.getStatus() != CentreStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "This centre is not active, so referrals cannot be submitted yet.");
        }
        if (details.referralReason() == null || details.referralReason().isBlank()) {
            throw new IllegalArgumentException(
                    "A referral reason is required. It is what the consulting doctor reads "
                            + "before the session and the only clinical context they have.");
        }

        CentreReferral referral = new CentreReferral();
        referral.setCentre(centre);
        referral.setCentrePatient(patient);
        referral.setReference("REF-" + Tokens.generateRecoveryCode().replace("-", ""));
        referral.setReferralReason(details.referralReason());
        referral.setAssessment(details.assessment());
        referral.setCurrentCondition(details.currentCondition());
        referral.setRelevantMedicines(details.relevantMedicines());
        referral.setPreviousResults(details.previousResults());
        referral.setUrgency(details.urgency() == null ? ReferralUrgency.ROUTINE : details.urgency());
        referral.setStatus(ReferralStatus.DRAFT);

        return referralRepository.save(referral);
    }

    /**
     * Corrects a referral before it is sent, or after FNPH returned it.
     *
     * FNPH returns a referral asking for something specific ("add the current
     * medication"). There was no way to add it: resubmitting sent the same
     * text back, so the hub returned it again.
     */
    @Transactional
    public CentreReferral update(String referralPublicId, ReferralDetails details) {
        CentreReferral referral = require(referralPublicId);
        if (referral.getStatus() != ReferralStatus.DRAFT && referral.getStatus() != ReferralStatus.RETURNED) {
            throw new BusinessRuleException(
                    "Only a draft or a referral FNPH returned can be changed. This one is "
                            + referral.getStatus() + ".");
        }
        if (details.referralReason() == null || details.referralReason().isBlank()) {
            throw new IllegalArgumentException(
                    "A referral reason is required. It is what the consulting doctor reads "
                            + "before the session.");
        }
        referral.setReferralReason(details.referralReason());
        referral.setAssessment(details.assessment());
        referral.setCurrentCondition(details.currentCondition());
        referral.setRelevantMedicines(details.relevantMedicines());
        referral.setPreviousResults(details.previousResults());
        if (details.urgency() != null) {
            referral.setUrgency(details.urgency());
        }
        CentreReferral saved = referralRepository.save(referral);
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("CentreReferral")
                .entityId(saved.getId())
                .details("Referral %s edited while %s".formatted(saved.getReference(), saved.getStatus()))
                .build());
        return saved;
    }

    /**
     * Records the patient's consent and submits the referral.
     *
     * Consent is captured before submission rather than after, because a
     * referral in front of a doctor is a referral the patient already agreed to.
     */
    @Transactional
    public CentreReferral submit(String referralPublicId, String consentVersion,
                                 String consentWitnessedBy) {
        CentreReferral referral = require(referralPublicId);

        // Create checked this; submit did not, so a draft written while active
        // could still reach the FNPH queue after the centre was suspended.
        if (referral.getCentre().getStatus() != CentreStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "This centre is not active, so referrals cannot be submitted. Contact FNPH.");
        }
        if (referral.getStatus() != ReferralStatus.DRAFT
                && referral.getStatus() != ReferralStatus.RETURNED) {
            throw new BusinessRuleException("That referral has already been submitted");
        }
        if (consentVersion == null || consentWitnessedBy == null || consentWitnessedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "Record the consent version and who at the centre witnessed the patient "
                            + "consenting.");
        }
        // The version must be the published centre consent. Anything else is a
        // record that the patient agreed to a text that does not exist.
        String published = triageService.activeConsent("CENTRE").getVersion();
        if (!published.equals(consentVersion)) {
            throw new IllegalArgumentException(
                    "The consent text has changed. Read the current version to the patient "
                            + "and submit again.");
        }

        LocalDateTime now = LocalDateTime.now();
        referral.setConsentVersion(consentVersion);
        referral.setConsentAcceptedAt(now);
        referral.setConsentAcceptedBy(consentWitnessedBy);
        referral.setStatus(ReferralStatus.SUBMITTED);
        referral.setSubmittedAt(now);
        referral.setSubmittedBy(CurrentUser.usernameOrSystem());
        referralRepository.save(referral);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("CentreReferral")
                .entityId(referral.getId())
                .details("Referral %s submitted by %s"
                        .formatted(referral.getReference(), referral.getCentre().getCode()))
                .build());

        return referral;
    }

    /** Every referral for one patient, newest first. The clinical history. */
    @Transactional(readOnly = true)
    public List<CentreReferral> historyFor(Long centrePatientId) {
        return referralRepository.findAllByCentrePatientIdOrderByCreatedAtDesc(centrePatientId);
    }

    // -----------------------------------------------------------------
    // Incoming queue
    // -----------------------------------------------------------------

    /**
     * Delivers a released bundle into the centre's queue.
     *
     * Called when the Hub Coordinator releases. The centre receives the
     * complete bundle, never part of it.
     */
    @Transactional
    public CentreBundleReceipt deliver(ReleaseBundle bundle, CentreAppointment appointment) {
        return receiptRepository.findByBundleId(bundle.getId()).orElseGet(() -> {
            CentreBundleReceipt receipt = new CentreBundleReceipt();
            receipt.setCentre(appointment.getCentre());
            receipt.setBundle(bundle);
            receipt.setCentreAppointment(appointment);
            receipt.setCentrePatient(appointment.getCentrePatient());
            receipt.setDeliveredAt(LocalDateTime.now());
            CentreBundleReceipt saved = receiptRepository.save(receipt);

            // To the centre's desk, not a named coordinator. Whoever is on
            // duty picks it up and it leaves everyone's list at once.
            notifications.notifyRole("CENTRE_HUB_COORDINATOR", appointment.getCentre(),
                    NotificationType.PRESCRIPTION_RELEASED,
                    "Care bundle received",
                    "A completed care bundle has arrived for %s (%s)."
                            .formatted(appointment.getCentrePatient().getFirstName(),
                                    appointment.getCentrePatient().getCentrePatientId()),
                    "/centre/incoming/" + saved.getPublicId(),
                    "CentreBundleReceipt", saved.getId());
            // The services that act on it hear too. Only the coordinator was
            // told, so the pharmacy learned of a prescription by checking.
            for (String role : java.util.List.of("CENTRE_PHARMACY", "CENTRE_LABORATORY", "CENTRE_HIM")) {
                notifications.notifyRole(role, appointment.getCentre(),
                        NotificationType.PRESCRIPTION_RELEASED,
                        "Care bundle received",
                        "A completed care bundle has arrived for %s (%s)."
                                .formatted(appointment.getCentrePatient().getFirstName(),
                                        appointment.getCentrePatient().getCentrePatientId()),
                        "/centre/incoming/" + saved.getPublicId(),
                        "CentreBundleReceipt", saved.getId());
            }

            log.info("Bundle {} delivered to centre {}",
                    bundle.getPublicId(), appointment.getCentre().getCode());
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> incoming() {
        return receiptRepository.findAllByCentreIdAndTreatedAtIsNullOrderByDeliveredAtAsc(
                TenantContext.requireCentreId());
    }


    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> treatedHistory() {
        return receiptRepository.findAllByCentreIdAndTreatedAtIsNotNullOrderByTreatedAtDesc(
                TenantContext.requireCentreId());
    }

    /**
     * What one service still has to act on. Coordination keeps the receipt's
     * own treated state; the pharmacy, laboratory and records each have their
     * own, so one of them finishing no longer clears the bundle for the rest.
     */
    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> incoming(CentreCapability capability) {
        if (capability == CentreCapability.COORDINATION) {
            return incoming();
        }
        return receiptRepository.findAwaitingByCentreId(
                TenantContext.requireCentreId(), capability, capability.needs.name());
    }

    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> treatedHistory(CentreCapability capability) {
        if (capability == CentreCapability.COORDINATION) {
            return treatedHistory();
        }
        return receiptRepository.findActedByCentreId(TenantContext.requireCentreId(), capability);
    }

    @Transactional(readOnly = true)
    public List<CentreBundleAction> actionsOn(CentreBundleReceipt receipt) {
        return actionRepository.findAllByCentreIdAndReceiptIdOrderByActedAtAsc(
                receipt.getCentre().getId(), receipt.getId());
    }

    /**
     * Records what one service did with a bundle. The caller must hold that
     * service's role. Coordination also closes the receipt, which is what the
     * centre's "awaiting action" count reads. A second record by the same
     * service returns the first.
     */
    @Transactional
    public CentreBundleAction markTreated(String receiptPublicId, String notes, CentreCapability capability) {
        if (!holdsRoleFor(capability)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Your role does not act on bundles for " + capability.name().toLowerCase());
        }
        CentreBundleReceipt receipt = requireReceipt(receiptPublicId);
        var existing = actionRepository.findByCentreIdAndReceiptIdAndCapability(
                receipt.getCentre().getId(), receipt.getId(), capability);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Record what was done. Without it, nobody can say months later whether "
                            + "the prescription was dispensed.");
        }
        if (capability == CentreCapability.COORDINATION) {
            markTreated(receiptPublicId, notes);
        }
        CentreBundleAction action = new CentreBundleAction();
        action.setCentre(receipt.getCentre());
        action.setReceipt(receipt);
        action.setCapability(capability);
        action.setActedAt(LocalDateTime.now());
        action.setActedBy(CurrentUser.usernameOrSystem());
        action.setNotes(notes.trim());
        CentreBundleAction saved = actionRepository.save(action);
        if (capability != CentreCapability.COORDINATION) {
            auditService.record(AuditService.AuditEvent.builder()
                    .action(AuditAction.RECORD_UPDATED)
                    .entityType("CentreBundleReceipt")
                    .entityId(receipt.getId())
                    .details("Care bundle acted on by %s at %s"
                            .formatted(capability.name().toLowerCase(), receipt.getCentre().getCode()))
                    .reason(notes)
                    .build());
        }
        return saved;
    }

    private static boolean holdsRoleFor(CentreCapability capability) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> capability.roleCodes.stream()
                        .anyMatch(code -> ("ROLE_" + code).equals(a.getAuthority())));
    }

    /** One receipt at the caller's centre, without recording an open. */
    @Transactional(readOnly = true)
    public CentreBundleReceipt receipt(String receiptPublicId) {
        return requireReceipt(receiptPublicId);
    }

    @Transactional
    public CentreBundleReceipt open(String receiptPublicId) {
        CentreBundleReceipt receipt = requireReceipt(receiptPublicId);

        if (receipt.getFirstOpenedAt() == null) {
            receipt.setFirstOpenedAt(LocalDateTime.now());
            receipt.setFirstOpenedBy(CurrentUser.usernameOrSystem());
            receiptRepository.save(receipt);
        }
        return receipt;
    }

    /**
     * Marks an item treated and moves it to history.
     *
     * The notes are required. "Treated" with no record of what was done leaves
     * the centre unable to answer, months later, whether a prescription was
     * actually dispensed.
     */
    @Transactional
    public CentreBundleReceipt markTreated(String receiptPublicId, String notes) {
        CentreBundleReceipt receipt = requireReceipt(receiptPublicId);

        if (receipt.getTreatedAt() != null) {
            return receipt;
        }
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Record what was done. Without it, nobody can say months later whether "
                            + "the prescription was dispensed.");
        }

        receipt.setTreatedAt(LocalDateTime.now());
        receipt.setTreatedBy(CurrentUser.usernameOrSystem());
        receipt.setTreatmentNotes(notes);
        receiptRepository.save(receipt);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("CentreBundleReceipt")
                .entityId(receipt.getId())
                .details("Care bundle marked treated by " + receipt.getCentre().getCode())
                .reason(notes)
                .build());

        return receipt;
    }

    /**
     * What a centre may see about its own activity.
     *
     * Counts, never amounts. The specification is explicit that centres do not
     * see wallet balances, so this returns how many consultations happened and
     * how much work is outstanding, which is what a coordinator actually runs
     * their week on.
     */
    @Transactional(readOnly = true)
    public UtilisationSummary utilisation() {
        Long centreId = TenantContext.requireCentreId();
        return new UtilisationSummary(
                referralRepository.countByCentreIdAndStatus(centreId, ReferralStatus.SUBMITTED),
                referralRepository.countByCentreIdAndStatus(centreId, ReferralStatus.SCHEDULED),
                referralRepository.countByCentreIdAndStatus(centreId, ReferralStatus.COMPLETED),
                receiptRepository.countByCentreIdAndTreatedAtIsNull(centreId));
    }

    private CentreBundleReceipt requireReceipt(String publicId) {
        return receiptRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), publicId)
                .orElseThrow(() -> new IllegalArgumentException("No such item"));
    }

    private CentreReferral require(String publicId) {
        return referralRepository
                .findByCentreIdAndPublicId(TenantContext.requireCentreId(), publicId)
                .orElseThrow(() -> new IllegalArgumentException("No such referral"));
    }


    public record ReferralDetails(String referralReason, String assessment,
                                  String currentCondition, String relevantMedicines,
                                  String previousResults, ReferralUrgency urgency) {
    }

    /** Counts only. No naira figure appears here by design. */
    public record UtilisationSummary(long referralsAwaitingScheduling, long referralsScheduled,
                                     long consultationsCompleted, long bundlesAwaitingAction) {
    }
}
