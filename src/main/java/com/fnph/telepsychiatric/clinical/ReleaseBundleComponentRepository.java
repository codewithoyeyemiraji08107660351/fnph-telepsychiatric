package com.fnph.telepsychiatric.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseBundleComponentRepository
        extends JpaRepository<ReleaseBundleComponent, Long> {

    List<ReleaseBundleComponent> findAllByBundleId(Long bundleId);

    Optional<ReleaseBundleComponent> findByBundleIdAndComponentType(
            Long bundleId, ComponentType componentType);
}
