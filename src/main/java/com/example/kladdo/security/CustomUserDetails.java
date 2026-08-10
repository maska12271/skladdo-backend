package com.example.kladdo.security;

import com.example.kladdo.model.CompanyType;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal backed by a {@link User}. Exposes the company id and role so they are
 * available to controllers/services and can be embedded in the JWT.
 *
 * <p>An account has a fixed <em>home</em> company (the one that owns the login) and an <em>active</em>
 * company - the tenant the current request works in. For everyone they are the same. For staff of a
 * warehouse operator they differ while the user has switched into a client company they are connected to
 * (see {@link com.example.kladdo.model.WarehouseConnection}); such a session is a
 * {@linkplain #isPartnerSession() partner session}, and it deliberately drops the user's own role to
 * {@link Role#WAREHOUSE} - nobody is an owner or administrator of a company that is not theirs.</p>
 */
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final Role role;
    private final Long homeCompanyId;
    private final CompanyType homeCompanyType;
    private final Long activeCompanyId;
    private final boolean active;
    private final boolean archived;
    /** Price visibility for a partner session, taken from the connection rather than the account. */
    private final boolean partnerCanSeePrices;

    public CustomUserDetails(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.homeCompanyId = user.getCompany() != null ? user.getCompany().getId() : null;
        this.homeCompanyType = user.getCompany() != null ? user.getCompany().getType() : CompanyType.BUSINESS;
        this.activeCompanyId = this.homeCompanyId;
        this.active = Boolean.TRUE.equals(user.getActive());
        this.archived = Boolean.TRUE.equals(user.getArchived());
        this.partnerCanSeePrices = false;
    }

    private CustomUserDetails(CustomUserDetails source, Long activeCompanyId, boolean partnerCanSeePrices) {
        this.id = source.id;
        this.email = source.email;
        this.passwordHash = source.passwordHash;
        this.fullName = source.fullName;
        // Working inside someone else's company is warehouse-staff access, whatever the account is at home.
        this.role = Role.WAREHOUSE;
        this.homeCompanyId = source.homeCompanyId;
        this.homeCompanyType = source.homeCompanyType;
        this.activeCompanyId = activeCompanyId;
        this.active = source.active;
        this.archived = source.archived;
        this.partnerCanSeePrices = partnerCanSeePrices;
    }

    /**
     * A copy of this principal acting inside a connected client company. Callers must have verified the
     * {@link com.example.kladdo.model.WarehouseConnection} first - this method grants, it does not check.
     */
    public CustomUserDetails actingIn(Long clientCompanyId, boolean canSeePrices) {
        return new CustomUserDetails(this, clientCompanyId, canSeePrices);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    /** The effective role for this request - {@link Role#WAREHOUSE} throughout a partner session. */
    public Role getRole() {
        return role;
    }

    /** The tenant this request works in. Everything data-scoped should use this. */
    public Long getCompanyId() {
        return activeCompanyId;
    }

    /** The company that owns the login, which never changes for the life of the account. */
    public Long getHomeCompanyId() {
        return homeCompanyId;
    }

    /** The kind of company that owns the login. Never changes, not even inside a client's tenant. */
    public CompanyType getHomeCompanyType() {
        return homeCompanyType == null ? CompanyType.BUSINESS : homeCompanyType;
    }

    /** True when the user has switched into a client company they merely operate a warehouse for. */
    public boolean isPartnerSession() {
        return homeCompanyId != null && !homeCompanyId.equals(activeCompanyId);
    }

    /**
     * True while this request works in a {@link CompanyType#WAREHOUSE} account's <em>own</em> tenant - the
     * one place that owns no catalogue, orders or tenders. Inside a client company the same login is an
     * ordinary partner session, so this is false there.
     */
    public boolean isWarehouseAccountAtHome() {
        return getHomeCompanyType() == CompanyType.WAREHOUSE && !isPartnerSession();
    }

    /** Whether this partner session may see the client's monetary values. Meaningless when not switched. */
    public boolean isPartnerCanSeePrices() {
        return partnerCanSeePrices;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !archived;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active && !archived;
    }
}
