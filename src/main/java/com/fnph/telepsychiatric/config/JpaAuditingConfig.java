package com.fnph.telepsychiatric.config;

import com.fnph.telepsychiatric.security.SecurityUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Without @EnableJpaAuditing the @CreatedDate and @LastModifiedDate fields on
 * BaseEntity are never populated. Because created_at is declared NOT NULL,
 * every insert fails at runtime. This was missing.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.of("system");
            }
            Object principal = authentication.getPrincipal();
            if (principal instanceof SecurityUser user) {
                return Optional.of(user.getUsername());
            }
            return Optional.of(authentication.getName());
        };
    }
}
