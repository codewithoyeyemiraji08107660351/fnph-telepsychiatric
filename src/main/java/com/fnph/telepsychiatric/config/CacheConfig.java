package com.fnph.telepsychiatric.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Local cache for governed configuration.
 *
 * Every appointment calculation reads several settings. Without a cache that
 * puts a database query on the hot path of the scheduling engine for values
 * that change a few times a year.
 *
 * A short expiry as well as eviction on write. Eviction handles changes made
 * through this instance; the expiry bounds how long a second application node
 * can keep serving a stale fee after the first one changed it. Two nodes are
 * the production topology, so this is not hypothetical.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("systemConfiguration");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }
}
