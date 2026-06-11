package com.example.tenderapp.security;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Supplies the current tenant (company) id to Hibernate's discriminator-based multi-tenancy.
 * Every entity annotated with {@code @TenantId} is automatically filtered by, and stamped with,
 * the value returned here.
 *
 * <p>When no tenant is set (e.g. during login, before authentication) a sentinel id of {@code -1}
 * is returned. Business queries then resolve to "company -1", which matches no rows - so there is
 * never any chance of leaking another company's data.</p>
 */
@Component
public class CompanyTenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    /** Sentinel value used when no company is bound to the current request. */
    public static final Long NO_TENANT = -1L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long companyId = TenantContext.getCompanyId();
        return companyId != null ? companyId : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
