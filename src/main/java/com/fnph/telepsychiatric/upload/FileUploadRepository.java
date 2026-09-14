package com.fnph.telepsychiatric.upload;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Uploaded files, across all three pathways.
 *
 * A FileUpload may belong to an FNPH patient, to a centre, or to neither when
 * staff upload it, so a centre predicate here would refuse the FNPH pathway
 * where centre is null. Ownership is enforced in
 * {@code UploadService.assertReadable} instead, which is also where the scan
 * gate lives.
 */
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {

    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "resolved then checked by UploadService.assertReadable, which covers "
                    + "patient, centre and staff principals and gates on scan status")
    Optional<FileUpload> findByPublicId(String publicId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "patientId is CurrentUser.require().getPatientId(), from the session")
    List<FileUpload> findAllByPatientIdOrderByUploadedAtDesc(Long patientId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "uploadedBy is CurrentUser.usernameOrSystem() for the caller's own "
                    + "uploads, never a username from the request")
    List<FileUpload> findAllByUploadedByOrderByUploadedAtDesc(String uploadedBy);

    /** ICT's quarantine list, across every centre. */
    @UnscopedQuery(value = UnscopedQuery.Reason.HOSPITAL_QUEUE,
            detail = "GET /admin/uploads/quarantined is guarded by upload.quarantine, held by "
                    + "ICT and the Central Administrator only")
    List<FileUpload> findAllByScanStatusOrderByUploadedAtDesc(ScanStatus scanStatus);
}