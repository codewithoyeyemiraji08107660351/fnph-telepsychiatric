package com.fnph.telepsychiatric.configuration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigurationRepository extends JpaRepository<SystemConfiguration, Long> {

    Optional<SystemConfiguration> findByConfigKey(String configKey);

    Optional<SystemConfiguration> findByPublicId(String publicId);

    List<SystemConfiguration> findAllByOrderByCategoryAscConfigKeyAsc();

    List<SystemConfiguration> findAllByCategoryOrderByConfigKeyAsc(String category);
}
