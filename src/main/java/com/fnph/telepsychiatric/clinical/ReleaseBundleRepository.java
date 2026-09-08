package com.fnph.telepsychiatric.clinical;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReleaseBundleRepository extends JpaRepository<ReleaseBundle, Long> {

    Optional<ReleaseBundle> findByPublicId(String publicId);
    Optional<ReleaseBundle> findByAppointmentId(Long appointmentId);
    Optional<ReleaseBundle> findByCentreAppointmentId(Long centreAppointmentId);

    Page<ReleaseBundle> findAllByStatusOrderByCreatedAtAsc(BundleStatus status, Pageable pageable);

    long countByStatus(BundleStatus status);
}
