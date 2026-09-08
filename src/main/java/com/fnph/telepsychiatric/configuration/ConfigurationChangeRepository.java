package com.fnph.telepsychiatric.configuration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfigurationChangeRepository extends JpaRepository<ConfigurationChange, Long> {

    List<ConfigurationChange> findAllByConfigKeyOrderByChangedAtDesc(String configKey);
}
