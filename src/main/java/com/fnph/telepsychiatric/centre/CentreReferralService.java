package com.fnph.telepsychiatric.centre;

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
    private final CentreWalletService walletService;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    @Transactional
    public CentreReferral create(Center centre, CentrePatient patient, ReferralDetails details) {
        if (centre.getStatus() != CentreStatus.ACTIVE) {
            throw new IllegalStateException(
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
     * Records the patient's consent and submits the referral.
     *
     * Consent is captured before submission rather than after, because a
     * referral in front of a doctor is a referral the patient already agreed to.
     */
    @Transactional
    public CentreReferral submit(String referralPublicId, String consentVersion,
                                 String consentWitnessedBy) {
        CentreReferral referral = require(referralPublicId);

        if (referral.getStatus() != ReferralStatus.DRAFT
                && referral.getStatus() != ReferralStatus.RETURNED) {
            throw new IllegalStateException("That referral has already been submitted");
        }
        if (consentVersion == null || consentWitnessedBy == null || consentWitnessedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "Record the consent version and who at the centre witnessed the patient "
                            + "consenting.");
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

            log.info("Bundle {} delivered to centre {}",
                    bundle.getPublicId(), appointment.getCentre().getCode());
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> incoming() {
        return receiptRepository.findAllByTreatedAtIsNullOrderByDeliveredAtAsc();
    }

    @Transactional(readOnly = true)
    public List<CentreBundleReceipt> treatedHistory() {
        return receiptRepository.findAllByTreatedAtIsNotNullOrderByTreatedAtDesc();
    }

    @Transactional
    public CentreBundleReceipt open(String receiptPublicId) {
        CentreBundleReceipt receipt = receiptRepository.findByPublicId(receiptPublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such item"));

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
        CentreBundleReceipt receipt = receiptRepository.findByPublicId(receiptPublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such item"));

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
        return new UtilisationSummary(
                referralRepository.countByStatus(ReferralStatus.SUBMITTED),
                referralRepository.countByStatus(ReferralStatus.SCHEDULED),
                referralRepository.countByStatus(ReferralStatus.COMPLETED),
                receiptRepository.countByTreatedAtIsNull());
    }

    private CentreReferral require(String publicId) {
        return referralRepository.findByPublicId(publicId)
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
