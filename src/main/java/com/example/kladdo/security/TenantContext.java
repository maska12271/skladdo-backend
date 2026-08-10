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

    /**
     * Runs {@code action} with {@code companyId} bound as the current tenant and returns its result,
     * restoring the previous binding afterwards.
     *
     * <p>Needed by background jobs: a scheduled thread never passes through
     * {@link JwtAuthenticationFilter}, so nothing has bound a tenant for it. The binding must be in place
     * <em>before</em> the transaction opens - Hibernate resolves the tenant id when the session is
     * created, so changing it mid-transaction has no effect.</p>
     */
    public static <T> T callAs(Long companyId, java.util.function.Supplier<T> action) {
        Long previous = CURRENT_COMPANY.get();
        CURRENT_COMPANY.set(companyId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                CURRENT_COMPANY.set(previous);
            } else {
                CURRENT_COMPANY.remove();
            }
        }
    }
}
