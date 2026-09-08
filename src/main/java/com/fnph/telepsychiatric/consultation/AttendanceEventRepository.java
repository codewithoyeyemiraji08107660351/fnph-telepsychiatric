package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    List<AttendanceEvent> findAllByConsultationIdOrderByOccurredAtAsc(Long consultationId);

    /** Exactly-once for provider webhooks: a repeat delivery is already here. */
    boolean existsByProviderEventId(String providerEventId);

    boolean existsByConsultationIdAndParticipantRoleAndEventType(
            Long consultationId, ParticipantRole role, AttendanceEventType eventType);
}
