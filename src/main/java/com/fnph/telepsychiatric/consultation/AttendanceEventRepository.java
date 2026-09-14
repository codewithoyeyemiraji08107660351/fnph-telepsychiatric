package com.fnph.telepsychiatric.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    List<AttendanceEvent> findAllByConsultationIdOrderByOccurredAtAsc(Long consultationId);

    /** Exactly-once for provider webhooks: a repeat delivery is already here. */
    boolean existsByProviderEventId(String providerEventId);

    boolean existsByConsultationIdAndParticipantRoleAndEventType(
            Long consultationId, ParticipantRole role, AttendanceEventType eventType);

    /**
     * Whether a participant ever joined. The centre pathway has no
     * centreJoinedAt column, so attendance is the record, which is also the
     * append-only evidence a disputed no-show is settled from.
     */
    boolean existsByCentreConsultationIdAndParticipantRoleAndEventType(
            Long centreConsultationId, ParticipantRole role, AttendanceEventType eventType);
}
