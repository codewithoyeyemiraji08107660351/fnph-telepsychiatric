package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Care bundles delivered to a centre.
 *
 * <h2>The queue methods were the widest leak in the centre pathway</h2>
 *
 * {@code findAllByTreatedAtIsNullOrderByDeliveredAtAsc} had no centre
 * predicate, and it backs {@code GET /api/v1/centres/me/incoming}, which is the
 * coordinator's main screen. Each row carries the centre-local patient
 * identifier and the patient's full name, so a coordinator at one centre could
 * read the names of patients at the other 22 by logging in. The altered-
 * identifier case at least needed a crafted request; this needed nothing.
 *
 * {@code findByPublicId} was worse in kind if not in breadth, because
 * {@code markTreated} writes through it: one centre could close another's
 * bundle and leave its own username on the record.
 *
 * Every method now names the centre or carries an {@link UnscopedQuery} reason.
 */
public interface CentreBundleReceiptRepository extends JpaRepository<CentreBundleReceipt, Long> {

    Optional<CentreBundleReceipt> findByCentreIdAndPublicId(Long centreId, String publicId);

    /**
     * Idempotency check on delivery.
     *
     * Called only by {@code deliver}, with a bundle the Hub Coordinator has
     * already resolved and released. The bundle decides the centre, so the
     * caller is not choosing one.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "bundle id comes from a ReleaseBundle the releasing hospital principal "
                    + "already resolved; the receipt's centre is taken from the appointment")
    Optional<CentreBundleReceipt> findByBundleId(Long bundleId);

    /** The incoming queue for one centre: delivered, not yet treated. */
    List<CentreBundleReceipt> findAllByCentreIdAndTreatedAtIsNullOrderByDeliveredAtAsc(
            Long centreId);

    /** History for one centre: treated, most recent first. */
    List<CentreBundleReceipt> findAllByCentreIdAndTreatedAtIsNotNullOrderByTreatedAtDesc(
            Long centreId);

    long countByCentreIdAndTreatedAtIsNull(Long centreId);

    /**
     * Bundles one service at a centre has not yet acted on, oldest first,
     * limited to bundles that contain that service's work: the pharmacy sees
     * bundles with a prescription, the laboratory those with an investigation
     * request, coordination and records see every bundle.
     */
    @Query("""
           select r from CentreBundleReceipt r
           join fetch r.centrePatient
           join fetch r.centreAppointment
           where r.centre.id = :centreId
             and not exists (select a.id from CentreBundleAction a
                             where a.receipt.id = r.id and a.capability = :capability)
             and (:needs = 'ANY'
                  or (:needs = 'PRESCRIPTION' and exists (
                        select p.id from Prescription p where p.bundle.id = r.bundle.id))
                  or (:needs = 'INVESTIGATION' and exists (
                        select i.id from Investigation i where i.bundle.id = r.bundle.id)))
           order by r.deliveredAt asc
           """)
    List<CentreBundleReceipt> findAwaitingByCentreId(@Param("centreId") Long centreId,
                                                     @Param("capability") CentreCapability capability,
                                                     @Param("needs") String needs);

    /** Bundles one service at a centre has acted on, most recently delivered first. */
    @Query("""
           select r from CentreBundleReceipt r
           join fetch r.centrePatient
           join fetch r.centreAppointment
           where r.centre.id = :centreId
             and exists (select a.id from CentreBundleAction a
                         where a.receipt.id = r.id and a.capability = :capability)
           order by r.deliveredAt desc
           """)
    List<CentreBundleReceipt> findActedByCentreId(@Param("centreId") Long centreId,
                                                  @Param("capability") CentreCapability capability);
}
