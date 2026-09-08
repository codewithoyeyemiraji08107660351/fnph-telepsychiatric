package com.fnph.telepsychiatric.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationRepository extends JpaRepository<ReconciliationRun, Long> {

    List<ReconciliationRun> findTop20ByOrderByStartedAtDesc();
}
