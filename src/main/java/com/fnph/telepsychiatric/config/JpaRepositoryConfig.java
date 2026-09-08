package com.fnph.telepsychiatric.config;

import com.fnph.telepsychiatric.tenancy.TenantAwareRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Makes {@link TenantAwareRepository} the base class for every Spring Data
 * repository in the application.
 *
 * This single line is what makes tenant isolation a property of the system
 * rather than a habit. A repository added in six months inherits it without
 * its author doing anything, and without knowing it exists.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.fnph.telepsychiatric",
        repositoryBaseClass = TenantAwareRepository.class)
public class JpaRepositoryConfig {
}
