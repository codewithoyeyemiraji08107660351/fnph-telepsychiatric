package com.fnph.telepsychiatric.tenancy;

/**
 * Thrown when a centre principal reaches a record belonging to another centre.
 *
 * Mapped to **404, not 403**. A 403 confirms the record exists, which turns a
 * URL into a way to discover that a named person is a patient at a specific
 * centre. "Not found" and "not yours" have to be indistinguishable from
 * outside; the difference is recorded in the audit trail, where staff with the
 * right permission can see it.
 */
public class CrossTenantAccessException extends RuntimeException {

    private final String entityType;
    private final Object entityId;
    private final Long owningCentreId;
    private final Long requestingCentreId;

    public CrossTenantAccessException(String entityType, Object entityId,
                                      Long owningCentreId, Long requestingCentreId) {
        super("Cross-tenant access blocked: %s %s belongs to centre %s, request came from centre %s"
                .formatted(entityType, entityId, owningCentreId, requestingCentreId));
        this.entityType = entityType;
        this.entityId = entityId;
        this.owningCentreId = owningCentreId;
        this.requestingCentreId = requestingCentreId;
    }

    public String getEntityType() {
        return entityType;
    }

    public Object getEntityId() {
        return entityId;
    }

    public Long getOwningCentreId() {
        return owningCentreId;
    }

    public Long getRequestingCentreId() {
        return requestingCentreId;
    }
}
