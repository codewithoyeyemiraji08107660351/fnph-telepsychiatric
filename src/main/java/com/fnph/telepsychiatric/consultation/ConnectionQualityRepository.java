package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConnectionQualityRepository extends JpaRepository<ConnectionQualityEvent, Long> {

    List<ConnectionQualityEvent> findAllByConsultationIdOrderByRecordedAtAsc(Long consultationId);
}
