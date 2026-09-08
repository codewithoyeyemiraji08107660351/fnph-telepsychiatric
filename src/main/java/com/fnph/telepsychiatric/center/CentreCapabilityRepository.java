package com.fnph.telepsychiatric.center;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CentreCapabilityRepository extends JpaRepository<CentreCapability, Long> {

    List<CentreCapability> findAllByCentreIdOrderByCapabilityAsc(Long centreId);

    Optional<CentreCapability> findByCentreIdAndCapability(Long centreId, CapabilityType capability);

    @Query("""
           select case when count(c) > 0 then true else false end
           from CentreCapability c
           where c.centre.id = :centreId and c.capability = :capability and c.isEnabled = true
           """)
    boolean isEnabled(@Param("centreId") Long centreId,
                      @Param("capability") CapabilityType capability);
}
