package com.fnph.telepsychiatric.ehr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EhrImportRepository extends JpaRepository<EhrVerificationImport, Long> {

    Optional<EhrVerificationImport> findByPublicId(String publicId);

    /** Exactly one snapshot is active. Enrolment matches against this one. */
    Optional<EhrVerificationImport> findFirstByStatus(ImportStatus status);

    boolean existsByFileChecksum(String checksum);

    List<EhrVerificationImport> findTop20ByOrderByUploadedAtDesc();
}
