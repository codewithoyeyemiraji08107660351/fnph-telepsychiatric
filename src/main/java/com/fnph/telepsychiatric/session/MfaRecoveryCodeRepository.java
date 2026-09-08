package com.fnph.telepsychiatric.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    Optional<MfaRecoveryCode> findByCodeHashAndUsedAtIsNull(String codeHash);

    long countByUserIdAndUsedAtIsNull(Long userId);

    @Modifying
    @Query("delete from MfaRecoveryCode c where c.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
