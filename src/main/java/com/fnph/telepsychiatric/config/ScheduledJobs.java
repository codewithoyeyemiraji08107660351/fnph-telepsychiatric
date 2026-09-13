package com.fnph.telepsychiatric.config;

import com.fnph.telepsychiatric.consultation.ConsultationService;
import com.fnph.telepsychiatric.document.IssuedDocumentService;
import com.fnph.telepsychiatric.payment.OutboxPublisher;
import com.fnph.telepsychiatric.scheduling.BookingService;
import com.fnph.telepsychiatric.storage.FilesystemStorageService;
import com.fnph.telepsychiatric.supervision.ViewAsService;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The jobs that keep the system consistent between requests.
 *
 * <h2>Every one of these runs with a stated tenant scope</h2>
 *
 * A job has no authenticated caller, so the tenant context defaults to
 * constrained with no centre and every tenant-owned query returns nothing.
 * That is the correct default and it means a job doing real work has to widen
 * scope deliberately, with a reason that lands in the audit trail.
 *
 * <h2>Fixed delay, not fixed rate</h2>
 *
 * Fixed rate starts a run whether or not the last one finished. A slow outbox
 * batch would then overlap itself and publish the same event twice.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobs {

    private final OutboxPublisher outboxPublisher;
    private final BookingService bookingService;
    private final ConsultationService consultationService;
    private final ViewAsService viewAsService;
    private final IssuedDocumentService documentService;
    private final FilesystemStorageService storageService;
    private final com.fnph.telepsychiatric.payment.ReconciliationService reconciliationService;

    /**
     * Turns verified payments into confirmed bookings.
     *
     * The most time-sensitive job here. A patient who has paid is watching a
     * screen, so the delay between payment and confirmation is delay they see.
     */
    @Scheduled(fixedDelay = 5_000)
    public void publishOutbox() {
        TenantContext.runAcrossAllCentres("Outbox publisher", () -> {
            int published = outboxPublisher.publishPending();
            if (published > 0) {
                log.debug("Published {} outbox event(s)", published);
            }
        });
    }

    /**
     * Returns lapsed slot holds to the pool.
     *
     * Without this an abandoned payment holds a clinic slot forever and the
     * schedule shows as full while nobody is booked.
     */
    @Scheduled(fixedDelay = 60_000)
    public void releaseLapsedHolds() {
        TenantContext.runAcrossAllCentres("Slot hold sweeper", () -> {
            int expired = bookingService.releaseLapsedHolds();
            if (expired > 0) {
                log.info("Expired {} unpaid appointment(s)", expired);
            }
        });
    }

    /**
     * Sends countdown warnings and ends sessions at their slot end.
     *
     * Every thirty seconds, because a warning at fourteen minutes remaining
     * instead of fifteen is fine and one at twelve is not.
     */
    @Scheduled(fixedDelay = 30_000)
    public void consultationClock() {
        TenantContext.runAcrossAllCentres("Session clock", () -> {
            int ended = consultationService.tick();
            if (ended > 0) {
                log.info("Closed {} consultation(s) at their slot end", ended);
            }
        });
    }

    /** Deletes rooms left open past their expiry. */
    @Scheduled(fixedDelay = 300_000)
    public void closeExpiredRooms() {
        TenantContext.runAcrossAllCentres("Video room cleanup",
                consultationService::closeExpiredRooms);
    }

    /**
     * Closes supervised sessions nobody ended.
     *
     * An administrator who walks away must not leave the mode running.
     */
    @Scheduled(fixedDelay = 300_000)
    public void closeExpiredSupervision() {
        TenantContext.runAcrossAllCentres("Supervised session expiry",
                viewAsService::closeExpiredSessions);
    }

    /** Marks documents past their validity. Nightly at 02:00. */
    @Scheduled(cron = "0 0 2 * * *")
    public void expireDocuments() {
        TenantContext.runAcrossAllCentres("Document expiry", () -> {
            int expired = documentService.expireLapsed();
            if (expired > 0) {
                log.info("Expired {} document(s)", expired);
            }
        });
    }

    /**
     * Removes files whose grace period has passed. Nightly at 03:00.
     *
     * Separate from the deletion request so a mistake can still be undone
     * during the window. A disk has no recycle bin.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void sweepDeletedFiles() {
        TenantContext.runAcrossAllCentres("Storage deletion sweeper", () -> {
            int removed = storageService.sweepDeletions();
            if (removed > 0) {
                log.info("Removed {} file(s) from disk", removed);
            }
        });
    }

    /**
     * Reconciles yesterday against the provider. Nightly at 01:00.
     *
     * Catches payments whose callback was lost. Without it a patient who paid
     * and could not book is only found when they complain.
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void reconcilePayments() {
        TenantContext.runAcrossAllCentres("Nightly reconciliation", () -> {
            var run = reconciliationService.run(
                    java.time.LocalDateTime.now().minusDays(1),
                    java.time.LocalDateTime.now(), "NIGHTLY");
            if (run.getExceptionCount() > 0) {
                log.error("Reconciliation raised {} exception(s) for Finance",
                        run.getExceptionCount());
            }
        });
    }

    /**
     * Warns when the disk is filling.
     *
     * Object storage grows silently. A disk stops, and when it stops nothing
     * can be uploaded and no document can be issued.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void checkFreeSpace() {
        int free = storageService.freeSpacePercent();
        if (free >= 0 && free < 20) {
            log.error("Storage is {}% free. Uploads and document issue will fail when it "
                    + "reaches zero.", free);
        }
    }
}
