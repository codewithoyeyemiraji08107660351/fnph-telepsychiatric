package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.document.DocumentType;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * The completeness check and the release.
 *
 * <h2>All or nothing</h2>
 *
 * The Hub Coordinator releases the whole bundle at once. There is no method to
 * release one component: a patient given a prescription while the investigation
 * request is still under review acts on half their care plan, and they have no
 * way to know that is what happened.
 *
 * <h2>The check is administrative, not clinical</h2>
 *
 * The coordinator confirms every expected component is done or deliberately not
 * needed. They do not judge the clinical content, and nothing here lets them
 * change it. That boundary is the specification's and it is worth keeping
 * visible: the person who checks the paperwork is not the person who decides
 * the treatment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReleaseService {

    private final ReleaseBundleRepository bundleRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final InvestigationRepository investigationRepository;
    private final FollowUpRepository followUpRepository;
    private final com.fnph.telepsychiatric.document.IssuedDocumentService documentService;
    private final com.fnph.telepsychiatric.document.render.DocumentRenderer renderer;
    private final com.fnph.telepsychiatric.centre.CentreReferralService centreDelivery;
    private final ReleaseBundleComponentRepository componentRepository;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    /**
     * Creates the bundle with a row per expected component.
     *
     * The clinical note is always required. The other three start as required
     * and become not-required the moment the doctor says none is needed, which
     * is what stops a bundle waiting forever for a document nobody intends to
     * write.
     */
    @Transactional
    public ReleaseBundle openFor(Appointment appointment) {
        return bundleRepository.findByAppointmentId(appointment.getId()).orElseGet(() -> {
            ReleaseBundle bundle = new ReleaseBundle();
            bundle.setAppointment(appointment);
            bundle.setStatus(BundleStatus.INCOMPLETE);
            ReleaseBundle saved = bundleRepository.save(bundle);

            Arrays.stream(ComponentType.values()).forEach(type -> {
                ReleaseBundleComponent component = new ReleaseBundleComponent();
                component.setBundle(saved);
                component.setComponentType(type);
                component.setIsRequired(type == ComponentType.CLINICAL_NOTE);
                componentRepository.save(component);
            });
            return saved;
        });
    }

    @Transactional
    public void markComponentComplete(ReleaseBundle bundle, ComponentType type) {
        if (bundle == null) {
            return;
        }
        componentRepository.findByBundleIdAndComponentType(bundle.getId(), type)
                .ifPresent(component -> {
                    component.setIsComplete(true);
                    component.setIsRequired(true);
                    component.setNotRequired(false);
                    componentRepository.save(component);
                });
        recomputeStatus(bundle.getId());
    }

    /**
     * Records that the doctor decided a component is not needed.
     *
     * A reason is required. "No prescription" with no explanation is
     * indistinguishable from an unfinished consultation when someone reads the
     * record a year later.
     */
    @Transactional
    public void markComponentNotRequired(ReleaseBundle bundle, ComponentType type, String reason) {
        if (bundle == null) {
            return;
        }
        if (type == ComponentType.CLINICAL_NOTE) {
            throw new IllegalArgumentException(
                    "A consultation always produces a clinical note");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Say why none is needed. Without it, this is indistinguishable from an "
                            + "unfinished consultation.");
        }
        componentRepository.findByBundleIdAndComponentType(bundle.getId(), type)
                .ifPresent(component -> {
                    component.setNotRequired(true);
                    component.setNotRequiredReason(reason);
                    component.setIsComplete(false);
                    componentRepository.save(component);
                });
        recomputeStatus(bundle.getId());
    }

    /** READY when every component is settled, INCOMPLETE otherwise. */
    @Transactional
    public BundleStatus recomputeStatus(Long bundleId) {
        ReleaseBundle bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("No such bundle"));

        if (bundle.getStatus() == BundleStatus.RELEASED
                || bundle.getStatus() == BundleStatus.BLOCKED) {
            return bundle.getStatus();
        }

        List<ReleaseBundleComponent> components =
                componentRepository.findAllByBundleId(bundleId);

        boolean allSettled = components.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsRequired()) || c.isSettled())
                .allMatch(ReleaseBundleComponent::isSettled);

        BundleStatus status = allSettled ? BundleStatus.READY : BundleStatus.INCOMPLETE;

        if (status == BundleStatus.READY && bundle.getStatus() != BundleStatus.READY) {
            notifications.notifyRole("HUB_COORDINATOR", null,
                    NotificationType.SUPPORT_TICKET_UPDATE,
                    "A clinical bundle is ready to release",
                    "Every component is complete or recorded as not needed.",
                    "/hub/releases/" + bundle.getPublicId(), "ReleaseBundle", bundle.getId());
        }

        bundle.setStatus(status);
        bundleRepository.save(bundle);
        return status;
    }

    /**
     * Releases everything at once.
     *
     * Refuses unless every component is settled, and names what is outstanding
     * so the coordinator knows who to chase rather than being told "not ready".
     */
    @Transactional
    public ReleaseBundle release(String bundlePublicId, String notes) {
        ReleaseBundle bundle = bundleRepository.findByPublicId(bundlePublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such bundle"));

        if (bundle.getStatus() == BundleStatus.RELEASED) {
            return bundle;
        }

        List<String> outstanding = componentRepository.findAllByBundleId(bundle.getId()).stream()
                .filter(c -> !c.isSettled())
                .map(c -> c.getComponentType().name())
                .toList();

        if (!outstanding.isEmpty()) {
            throw new IllegalStateException(
                    "Not ready. Still outstanding: " + String.join(", ", outstanding)
                            + ". Release is all or nothing, because a patient given part of "
                            + "their care plan cannot tell that is what happened.");
        }

        LocalDateTime now = LocalDateTime.now();
        bundle.setStatus(BundleStatus.RELEASED);
        bundle.setReleasedAt(now);
        bundle.setReleasedBy(CurrentUser.usernameOrSystem());
        bundle.setReleaseNotes(notes);
        bundleRepository.save(bundle);

        // The join that makes release mean something. Without it the bundle is
        // marked released, the patient is told their documents are ready, and
        // there is nothing for them to open.
        int issued = issueDocumentsFor(bundle);

        if (bundle.getAppointment() != null) {
            notifications.notifyPatient(bundle.getAppointment().getPatient(),
                    NotificationType.PRESCRIPTION_RELEASED,
                    "Your consultation documents are ready",
                    "%d document(s) from your consultation are now available in your account."
                            .formatted(issued),
                    "/portal/documents", "ReleaseBundle", bundle.getId());
        }

        // A centre bundle also lands in that centre's incoming queue. Without
        // this the centre pathway ends at the consultation and the centre never
        // receives anything.
        if (bundle.getCentreAppointment() != null) {
            centreDelivery.deliver(bundle, bundle.getCentreAppointment());
        }

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.BUNDLE_RELEASED)
                .entityType("ReleaseBundle")
                .entityId(bundle.getId())
                .details("Complete bundle released to the patient")
                .reason(notes)
                .build());

        log.info("Bundle {} released by {}", bundle.getPublicId(), bundle.getReleasedBy());
        return bundle;
    }

    /** Holds a bundle deliberately, with a reason. */
    @Transactional
    public ReleaseBundle block(String bundlePublicId, String reason) {
        ReleaseBundle bundle = bundleRepository.findByPublicId(bundlePublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such bundle"));

        if (bundle.getStatus() == BundleStatus.RELEASED) {
            throw new IllegalStateException(
                    "That bundle has already been released. Issue a superseding document "
                            + "instead of withdrawing what the patient already has.");
        }
        bundle.setStatus(BundleStatus.BLOCKED);
        bundle.setBlockedReason(reason);
        bundleRepository.save(bundle);
        return bundle;
    }

    /**
     * Issues and renders one document per released component.
     *
     * A failure to render one document must not stop the others being issued.
     * A patient who receives their prescription and not their follow-up note is
     * in a much better position than one who receives nothing because a
     * template had a typo in it.
     */
    private int issueDocumentsFor(ReleaseBundle bundle) {
        int issued = 0;
        var patient = bundle.getAppointment() == null ? null : bundle.getAppointment().getPatient();

        for (Prescription prescription : prescriptionRepository.findAllByBundleId(bundle.getId())) {
            try {
                var document = documentService.issue(
                        DocumentType.PRESCRIPTION, prescription.getId(), patient);
                byte[] pdf = renderer.renderPrescription(prescription,
                        document.getIssueNumber(),
                        documentService.verificationUrlFor(document.getId()),
                        document.getExpiresAt().toLocalDate());
                documentService.attachRendered(document.getId(), pdf, "application/pdf");
                prescription.setIssueNumber(document.getIssueNumber());
                prescription.setExpiryDate(document.getExpiresAt().toLocalDate());
                prescription.setStatus(ClinicalDocumentStatus.RELEASED);
                prescriptionRepository.save(prescription);
                issued++;
            } catch (Exception e) {
                log.error("Could not issue prescription {} in bundle {}: {}",
                        prescription.getPublicId(), bundle.getPublicId(), e.getMessage(), e);
            }
        }

        for (Investigation investigation : investigationRepository.findAllByBundleId(bundle.getId())) {
            try {
                var document = documentService.issue(
                        DocumentType.INVESTIGATION_REQUEST, investigation.getId(), patient);
                byte[] pdf = renderer.renderInvestigation(investigation,
                        document.getIssueNumber(),
                        documentService.verificationUrlFor(document.getId()),
                        document.getExpiresAt().toLocalDate());
                documentService.attachRendered(document.getId(), pdf, "application/pdf");
                investigation.setIssueNumber(document.getIssueNumber());
                investigation.setExpiryDate(document.getExpiresAt().toLocalDate());
                investigation.setStatus(ClinicalDocumentStatus.RELEASED);
                investigationRepository.save(investigation);
                issued++;
            } catch (Exception e) {
                log.error("Could not issue investigation {} in bundle {}: {}",
                        investigation.getPublicId(), bundle.getPublicId(), e.getMessage(), e);
            }
        }

        for (FollowUp followUp : followUpRepository.findAllByBundleId(bundle.getId())) {
            try {
                var document = documentService.issue(
                        DocumentType.FOLLOW_UP_RECOMMENDATION, followUp.getId(), patient);
                byte[] pdf = renderer.renderFollowUp(followUp, document.getIssueNumber(),
                        documentService.verificationUrlFor(document.getId()));
                documentService.attachRendered(document.getId(), pdf, "application/pdf");
                issued++;
            } catch (Exception e) {
                log.error("Could not issue follow-up {} in bundle {}: {}",
                        followUp.getPublicId(), bundle.getPublicId(), e.getMessage(), e);
            }
        }

        log.info("Bundle {} released with {} document(s)", bundle.getPublicId(), issued);
        return issued;
    }

    @Transactional(readOnly = true)
    public List<ReleaseBundleComponent> componentsOf(Long bundleId) {
        return componentRepository.findAllByBundleId(bundleId);
    }
}
