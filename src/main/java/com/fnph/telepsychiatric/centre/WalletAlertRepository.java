package com.fnph.telepsychiatric.centre;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletAlertRepository extends JpaRepository<WalletAlert, Long> {

    /** An open alert at this level, so a second is not raised on every booking. */
    @Query("""
           select a from WalletAlert a
           where a.centre.id = :centreId and a.alertLevel = :level and a.clearedAt is null
           """)
    Optional<WalletAlert> findOpen(@Param("centreId") Long centreId,
                                   @Param("level") String level);

    List<WalletAlert> findAllByClearedAtIsNullOrderByRaisedAtAsc();
}
