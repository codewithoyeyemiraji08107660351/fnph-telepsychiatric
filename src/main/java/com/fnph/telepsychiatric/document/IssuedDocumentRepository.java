package com.fnph.telepsychiatric.document;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Issued documents: the PDFs a patient or centre actually receives.
 *
 * <h2>The download allowance is the asset here, not just the content</h2>
 *
 * Each document has a limited number of downloads, usually one. That makes
 * {@code claimDownload} destructive: spending someone else's allowance denies
 * them the file permanently and leaves them the "already downloaded" message
 * for something they never got.
 *
 * The centre predicate is not what protects it. Ownership is checked in
 * {@code IssuedDocumentService.assertOwnedByCaller}, which until now covered
 * only patient principals and passed everyone else through, so the check went
 * in there rather than here.
 */
public interface IssuedDocumentRepository extends JpaRepository<IssuedDocument, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "resolved then checked by assertOwnedByCaller, which covers patient, "
                    + "centre and FNPH principals; a centre predicate here would refuse the "
                    + "FNPH pathway, where centre is null")
    Optional<IssuedDocument> findByPublicId(String publicId);

    /**
     * Public verification by issue number.
     *
     * The issue number is printed on the document and handed to whoever needs
     * to verify it, so presenting it is the authorisation. The endpoint returns
     * status and dates only, never content or a patient name, which is what
     * makes an unauthenticated lookup acceptable.
     */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SECRET,
            detail = "issue number is presented by the verifier who holds the document; the "
                    + "endpoint returns status and dates only, never content or patient name")
    Optional<IssuedDocument> findByIssueNumber(String issueNumber);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "type and sourceId identify the clinical record the document was "
                    + "generated from, already resolved by the issuing path")
    Optional<IssuedDocument> findByDocumentTypeAndSourceId(DocumentType type, Long sourceId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "patientId is the authenticated patient's own id, from the session "
                    + "rather than the request")
    List<IssuedDocument> findAllByPatientIdOrderByIssuedAtDesc(Long patientId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "bundleId comes from a ReleaseBundle resolved by the releasing hospital "
                    + "principal")
    List<IssuedDocument> findAllByBundleId(Long bundleId);

    /**
     * Claims one download in a single statement.
     *
     * The check and the increment happen together, so a patient retrying on a
     * poor connection cannot burn their single allowance twice and two devices
     * requesting at once cannot both succeed against a limit of one.
     *
     * Takes the internal id, never a public one, and the caller must have
     * established ownership first. It cannot do that itself: a centre predicate
     * would break the FNPH pathway, where centre is null.
     *
     * @return 1 when the download was claimed, 0 when the allowance is gone
     */
    @Modifying
    @Query("""
           update IssuedDocument d
              set d.downloadCount = d.downloadCount + 1,
                  d.isViewOnly = case when d.downloadCount + 1 >= d.maxDownloads
                                      then true else false end
            where d.id = :id
              and d.downloadCount < d.maxDownloads
              and d.status = com.fnph.telepsychiatric.document.DocumentStatus.ACTIVE
              and d.expiresAt > :now
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "internal id only, supplied by claimDownload after "
                    + "assertOwnedByCaller has passed; never reachable from a request")
    int claimDownload(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Marks documents past their validity. Run on a schedule. */
    @Modifying
    @Query("""
           update IssuedDocument d
              set d.status = com.fnph.telepsychiatric.document.DocumentStatus.EXPIRED
            where d.status = com.fnph.telepsychiatric.document.DocumentStatus.ACTIVE
              and d.expiresAt <= :now
           """)
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "scheduled sweep with no principal; expiry is time-based and applies to "
                    + "every centre, and scoping it would leave 22 centres' documents live")
    int expireLapsed(@Param("now") LocalDateTime now);
}