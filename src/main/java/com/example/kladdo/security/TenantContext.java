package com.example.kladdo.security;

/**
 * Holds the current company (tenant) id for the duration of a request on a thread-local basis.
 * Set by {@link JwtAuthenticationFilter} after authentication and read by
 * {@link CompanyTenantIdentifierResolver} so Hibernate can scope every query/insert by company.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_COMPANY = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setCompanyId(Long companyId) {
        CURRENT_COMPANY.set(companyId);
    }

    public static Long getCompanyId() {
        return CURRENT_COMPANY.get();
    }

    public static void clear() {
        CURRENT_COMPANY.remove();
    }
}
