package com.fnph.telepsychiatric.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IssuedDocumentRepository extends JpaRepository<IssuedDocument, Long> {

    Optional<IssuedDocument> findByPublicId(String publicId);
    Optional<IssuedDocument> findByIssueNumber(String issueNumber);
    Optional<IssuedDocument> findByDocumentTypeAndSourceId(DocumentType type, Long sourceId);

    List<IssuedDocument> findAllByPatientIdOrderByIssuedAtDesc(Long patientId);
    List<IssuedDocument> findAllByBundleId(Long bundleId);

    /**
     * Claims one download in a single statement.
     *
     * The check and the increment happen together, so a patient retrying on a
     * poor connection cannot burn their single allowance twice and two devices
     * requesting at once cannot both succeed against a limit of one.
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
    int claimDownload(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Marks documents past their validity. Run on a schedule. */
    @Modifying
    @Query("""
           update IssuedDocument d
              set d.status = com.fnph.telepsychiatric.document.DocumentStatus.EXPIRED
            where d.status = com.fnph.telepsychiatric.document.DocumentStatus.ACTIVE
              and d.expiresAt <= :now
           """)
    int expireLapsed(@Param("now") LocalDateTime now);
}
