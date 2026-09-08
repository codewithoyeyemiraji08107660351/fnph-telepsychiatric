package com.fnph.telepsychiatric.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    Optional<AccountToken> findByTokenHashAndPurpose(String tokenHash, AccountTokenPurpose purpose);

    /**
     * Issuing a new token invalidates any outstanding one for the same purpose,
     * so a forwarded or intercepted old email stops working as soon as a fresh
     * one is requested.
     */
    @Modifying
    @Query("""
           update AccountToken t set t.invalidatedAt = :now
            where t.user.id = :userId and t.purpose = :purpose
              and t.usedAt is null and t.invalidatedAt is null
           """)
    int invalidateOutstanding(@Param("userId") Long userId,
                              @Param("purpose") AccountTokenPurpose purpose,
                              @Param("now") LocalDateTime now);
}
