package com.fnph.telepsychiatric.configuration;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.security.CurrentUser;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Typed reads and audited writes for governed settings.
 *
 * <h2>Reads are cached, writes evict</h2>
 *
 * Every appointment calculation reads several of these. Hitting the database
 * each time would put a query on the hot path of the scheduling engine for
 * values that change a few times a year.
 *
 * <h2>Reads never fall back to a default</h2>
 *
 * A missing key throws. The tempting alternative is to return a sensible
 * default, and the failure mode of that is a typo in a key name silently
 * producing a 30-minute session where the configured value was 40, with nothing
 * anywhere saying so. Failing loudly at startup is better than being quietly
 * wrong for months.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigurationService {

    private static final String CACHE = "systemConfiguration";

    private final ConfigurationRepository repository;
    private final ConfigurationChangeRepository changeRepository;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Typed reads
    // -----------------------------------------------------------------

    @Cacheable(value = CACHE, key = "#key")
    @Transactional(readOnly = true)
    public String getString(String key) {
        return repository.findByConfigKey(key)
                .map(SystemConfiguration::getConfigValue)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No configuration key '" + key + "'. Keys are seeded by migration, so this "
                                + "is either a typo or a key that was never added."));
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

    public BigDecimal getDecimal(String key) {
        return new BigDecimal(getString(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    @Transactional(readOnly = true)
    public List<SystemConfiguration> listAll() {
        return repository.findAllByOrderByCategoryAscConfigKeyAsc();
    }

    @Transactional(readOnly = true)
    public List<SystemConfiguration> listByCategory(String category) {
        return repository.findAllByCategoryOrderByConfigKeyAsc(category);
    }

    @Transactional(readOnly = true)
    public List<ConfigurationChange> history(String key) {
        return changeRepository.findAllByConfigKeyOrderByChangedAtDesc(key);
    }

    // -----------------------------------------------------------------
    // Audited write
    // -----------------------------------------------------------------

    @CacheEvict(value = CACHE, key = "#key")
    @Transactional
    public SystemConfiguration update(String key, String newValue, String reason,
                                      LocalDateTime effectiveFrom, String ipAddress) {
        SystemConfiguration config = repository.findByConfigKey(key)
                .orElseThrow(() -> new EntityNotFoundException("No configuration key '" + key + "'"));

        validate(config, newValue);

        String previous = config.getConfigValue();
        if (previous.equals(newValue)) {
            // Not an error, but not a change either. Writing a history row for
            // it would fill the trail with entries an auditor has to read and
            // discard.
            return config;
        }

        String actor = CurrentUser.usernameOrSystem();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effective = effectiveFrom == null ? now : effectiveFrom;

        config.setConfigValue(newValue);
        config.setEffectiveFrom(effective);
        repository.save(config);

        ConfigurationChange change = new ConfigurationChange();
        change.setConfiguration(config);
        change.setConfigKey(key);
        change.setPreviousValue(previous);
        change.setNewValue(newValue);
        change.setReason(reason);
        change.setChangedBy(actor);
        change.setChangedAt(now);
        change.setEffectiveFrom(effective);
        change.setIpAddress(ipAddress);
        changeRepository.save(change);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.CONFIGURATION_CHANGED)
                .entityType("SystemConfiguration")
                .entityId(config.getId())
                .details("%s: %s -> %s".formatted(key, previous, newValue))
                .reason(reason)
                .beforeState(previous)
                .afterState(newValue)
                .ipAddress(ipAddress)
                .build());

        log.info("Configuration {} changed from {} to {} by {}: {}",
                key, previous, newValue, actor, reason);

        return config;
    }

    /**
     * Bounds and type checking.
     *
     * A consultation fee of zero or a session length of four hours is a typo.
     * Catching it here costs one method; catching it in a reconciliation report
     * a month later costs a refund process.
     */
    private void validate(SystemConfiguration config, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A value is required");
        }

        switch (config.getValueType()) {
            case INTEGER, DURATION_MINUTES -> {
                long parsed = parseLongOrFail(value, config.getConfigKey());
                checkBound(config.getMinValue(), parsed, true, config.getConfigKey());
                checkBound(config.getMaxValue(), parsed, false, config.getConfigKey());
            }
            case DECIMAL -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            config.getConfigKey() + " must be a decimal number");
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException(
                            config.getConfigKey() + " must be true or false");
                }
            }
            case STRING -> {
                if (value.length() > 1000) {
                    throw new IllegalArgumentException("That value is too long");
                }
            }
        }

        if (config.getAllowedValues() != null && !config.getAllowedValues().isBlank()) {
            List<String> allowed = Arrays.asList(config.getAllowedValues().split(","));
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException(
                        config.getConfigKey() + " must be one of " + allowed);
            }
        }
    }

    private long parseLongOrFail(String value, String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    private void checkBound(String bound, long value, boolean isMinimum, String key) {
        if (bound == null || bound.isBlank()) {
            return;
        }
        long limit = Long.parseLong(bound);
        if (isMinimum && value < limit) {
            throw new IllegalArgumentException(key + " cannot be below " + limit);
        }
        if (!isMinimum && value > limit) {
            throw new IllegalArgumentException(key + " cannot be above " + limit);
        }
    }
}
