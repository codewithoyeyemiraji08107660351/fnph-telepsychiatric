package com.fnph.telepsychiatric.upload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {

    Optional<FileUpload> findByPublicId(String publicId);

    List<FileUpload> findAllByPatientIdOrderByUploadedAtDesc(Long patientId);

    List<FileUpload> findAllByUploadedByOrderByUploadedAtDesc(String uploadedBy);

    List<FileUpload> findAllByReferenceIdOrderByUploadedAtDesc(String referenceId);

    List<FileUpload> findAllByScanStatusOrderByUploadedAtDesc(ScanStatus scanStatus);
}
