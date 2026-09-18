package com.fnph.telepsychiatric.ehr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManualEhrRecordRepository extends JpaRepository<ManualEhrRecord, Long> {
    Optional<ManualEhrRecord> findByPublicId(String publicId);
    Optional<ManualEhrRecord> findByEhrNumber(String ehrNumber);
    List<ManualEhrRecord> findAllByOrderByUpdatedAtDesc();
}
