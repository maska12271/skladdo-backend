package com.example.skladdo.security;

import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
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
 * (see {@link com.example.skladdo.model.WarehouseConnection}); such a session is a
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
    /**
     * Whether the company that owns this login is still usable. Suspending a company is a platform
     * decision, so it disables the account itself rather than merely hiding pages - see {@link #isEnabled()}.
     */
    private final boolean homeCompanyActive;
    /**
     * Whether this account operates the platform. Carried through a partner session unchanged: it is a
     * property of the person, not of the tenant they happen to be looking at.
     */
    private final boolean platformAdmin;
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
        this.homeCompanyActive = user.getCompany() == null || user.getCompany().isActive();
        this.platformAdmin = user.isPlatformAdmin();
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
        this.homeCompanyActive = source.homeCompanyActive;
        this.platformAdmin = source.platformAdmin;
        this.partnerCanSeePrices = partnerCanSeePrices;
    }

    /**
     * A copy of this principal acting inside a connected client company. Callers must have verified the
     * {@link com.example.skladdo.model.WarehouseConnection} first - this method grants, it does not check.
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

    /**
     * True while this request works in a tenant that owns no catalogue, orders or tenders of its own - a
     * warehouse account at home, or a {@link CompanyType#PLATFORM} company, which is Skladdo itself.
     *
     * <p>Both are the same rule for different reasons: one works only inside its clients' data, the other
     * runs the service rather than using it. Inside a client company a warehouse login is an ordinary
     * partner session, so this is false there; a platform company has no such elsewhere.</p>
     */
    public boolean ownsNoBusinessData() {
        return !getHomeCompanyType().ownsBusinessData() && !isPartnerSession();
    }

    /** True when this login belongs to Skladdo's own operator shell rather than to a customer. */
    public boolean isPlatformCompany() {
        return getHomeCompanyType() == CompanyType.PLATFORM;
    }

    /** Whether this partner session may see the client's monetary values. Meaningless when not switched. */
    public boolean isPartnerCanSeePrices() {
        return partnerCanSeePrices;
    }

    /** Whether this account administers the platform itself (every tenant), rather than one company. */
    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    /**
     * The account's role, plus {@code ROLE_PLATFORM_ADMIN} when it operates the platform.
     *
     * <p>The platform authority is deliberately <em>additional</em> rather than a {@link Role} value: it
     * governs a separate API ({@code /api/admin/**}) and must not widen anything inside a tenant. An
     * operator is still an ordinary member of whatever company owns their login.</p>
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        SimpleGrantedAuthority companyRole = new SimpleGrantedAuthority("ROLE_" + role.name());
        return platformAdmin
                ? List.of(companyRole, new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of(companyRole);
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

    /**
     * Whether this account may authenticate at all. Suspending a company disables every account in it:
     * {@code JwtAuthenticationFilter} checks this on every request, so a suspension takes effect on the
     * suspended users' next call rather than whenever their tokens happen to expire.
     *
     * <p>A platform operator is exempt from the company check on purpose. The flag is not reachable from
     * inside the application, so this widens nothing an operator could not already do - and without it,
     * suspending the company that owns the operator's own login would lock them out of the very panel
     * that undoes it, recoverable only by editing the database. {@code AdminService} refuses that
     * suspension outright; this is the second lock on the same door.</p>
     */
    @Override
    public boolean isEnabled() {
        return active && !archived && (homeCompanyActive || platformAdmin);
    }
}
