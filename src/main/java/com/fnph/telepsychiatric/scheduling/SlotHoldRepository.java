package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotHoldRepository extends JpaRepository<SlotHold, Long> {

    Optional<SlotHold> findFirstBySlotIdAndReleasedAtIsNullOrderByIdDesc(Long slotId);

    @Query("""
           select h from SlotHold h
           where h.releasedAt is null and h.expiresAt <= :now
           """)
    List<SlotHold> findLapsed(@Param("now") LocalDateTime now);

    @Modifying
    @Query("""
           update SlotHold h set h.releasedAt = :now, h.releaseReason = :reason
           where h.slot.id = :slotId and h.releasedAt is null
           """)
    int release(@Param("slotId") Long slotId, @Param("now") LocalDateTime now,
                @Param("reason") String reason);
}
