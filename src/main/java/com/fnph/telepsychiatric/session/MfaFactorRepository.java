package com.fnph.telepsychiatric.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MfaFactorRepository extends JpaRepository<MfaFactor, Long> {

    Optional<MfaFactor> findByUserIdAndType(Long userId, MfaType type);

    Optional<MfaFactor> findByUserIdAndTypeAndIsActiveTrue(Long userId, MfaType type);

    boolean existsByUserIdAndIsActiveTrue(Long userId);

    void deleteByUserIdAndType(Long userId, MfaType type);
}
