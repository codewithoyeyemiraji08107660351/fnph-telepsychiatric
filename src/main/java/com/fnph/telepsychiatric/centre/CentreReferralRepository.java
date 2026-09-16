package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Referrals from a Centre of Excellence to FNPH.
 *
 * <h2>The class comment used to say "tenant-scoped automatically by the
 * repository base class"</h2>
 *
 * It is not, and it never was. The Hibernate filter is enabled inside the
 * inherited methods on {@link
 * com.fnph.telepsychiatric.tenancy.TenantAwareRepository} and nowhere else, so
 * every derived query below ran without a centre predicate.
 *
 * That comment is the reason this went unnoticed: it told each reader the
 * problem was already handled. A comment asserting a control that does not
 * exist is worse than no comment, because it stops the next person checking.
 */
public interface CentreReferralRepository extends JpaRepository<CentreReferral, Long> {

    Optional<CentreReferral> findByCentreIdAndPublicId(Long centreId, String publicId);

    /**
     * Hub Coordinator lookup across centres.
     *
     * Reached from {@code /api/v1/hub/*} only. Kept separate from the scoped
     * method rather than making the centre nullable, so no centre-facing path
     * can reach every centre by passing null.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "Hub Coordinator schedules referrals from all 23 centres; no centre role "
                    + "holds appointment.approve or appointment.read")
    Optional<CentreReferral> findByPublicId(String publicId);

    /**
     * The reference is printed on paper the patient carries between the centre
     * and FNPH, so it is the identifier most likely to be read off a document
     * and typed into a request by someone who should not have it.
     */
    Optional<CentreReferral> findByCentreIdAndReference(Long centreId, String reference);

    /** FNPH's scheduling queue. Across centres by definition. */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "FNPH scheduling queue spans every centre; centre-facing counts use "
                    + "countByCentreIdAndStatus")
    List<CentreReferral> findAllByStatusOrderByCreatedAtAsc(ReferralStatus status);

    /**
     * Every referral for one patient, newest first. The clinical history.
     *
     * The patient id must come from a centre-scoped lookup. It does now:
     * {@code CentreController.history} resolves the patient through
     * {@code findByCentreIdAndPublicId} before this is called. Before that fix
     * this method was scoped to a patient the caller had no right to name.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "centrePatientId resolved via CentrePatientRepository"
                    + ".findByCentreIdAndPublicId at the call site")
    List<CentreReferral> findAllByCentrePatientIdOrderByCreatedAtDesc(Long centrePatientId);

    /**
     * Counts for one centre's own utilisation.
     *
     * The unscoped {@code countByStatus} it replaces backed
     * {@code /centres/me/utilisation}, so every centre was shown network
     * totals. No patient was named, but the numbers were wrong and they
     * disclosed how busy the other 22 centres are.
     */
    long countByCentreIdAndStatus(Long centreId, ReferralStatus status);

    /** One centre's referrals, newest first. Scoped by the centre in the signature. */
    List<CentreReferral> findAllByCentreIdOrderByCreatedAtDesc(Long centreId);

    List<CentreReferral> findAllByCentreIdAndStatusOrderByCreatedAtDesc(Long centreId, ReferralStatus status);
}