package uz.greenwhite.gateway.tenant;

/** Extracted tenant identifiers from a boundary payload. */
public record TenantPair(Long tenantId, Long extraTenantId) {}
