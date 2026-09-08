package com.fnph.telepsychiatric.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, Long> {

    Optional<CancellationRequest> findByPublicId(String publicId);
    List<CancellationRequest> findAllByStatusOrderByRequestedAtAsc(String status);
}
