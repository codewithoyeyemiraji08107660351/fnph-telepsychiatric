package com.fnph.telepsychiatric.center;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CenterRepository extends JpaRepository<Center, Long> {

    Optional<Center> findByCode(String code);

    Optional<Center> findByPublicId(String publicId);

    List<Center> findAllByIsActiveTrueOrderByNameAsc();

    List<Center> findAllByDeletedFalseOrderByNameAsc();
}
