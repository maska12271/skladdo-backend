package com.example.skladdo.dto;

import com.example.skladdo.model.AddonType;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.model.Warehouse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public representation of a user account. Never exposes the password hash.
 *
 * <p>{@code permissions} is populated for the authenticated user (login / {@code /me}) so the client
 * can decide which pages and actions to show. It is left {@code null} in bulk listings to avoid an
 * N+1 lookup - the admin editor fetches a user's permissions on demand.</p>
 *
 * <p>For a session profile, {@code companyId}/{@code companyName} describe the company the session is
 * <em>working in</em> while {@code homeCompanyId}/{@code homeCompanyName} describe the one that owns the
 * login. They differ only when a warehouse operator's user has switched into a client company, which
 * {@code partnerSession} flags: the client then reads {@code role} and {@code permissions} as the capped
 * partner access, not the account's own standing at home.</p>
 */
public record UserDto(
        Long id,
        String email,
        String fullName,
        Role role,
        Long companyId,
        String companyName,
        Boolean canSeePrices,
        Boolean active,
        Boolean archived,
        Boolean passwordSetupPending,
        String emailSignature,
        /** Interface language ("en"/"et"/"ru"); null on accounts that never set one. */
        String language,
        Map<PermissionModule, ModulePermissionDto> permissions,
        List<Long> warehouseIds,
        Long homeCompanyId,
        String homeCompanyName,
        /** True while working inside a client company via a warehouse connection. */
        Boolean partnerSession,
        /**
         * The account's standing in its <em>own</em> company, which {@code role} hides during a partner
         * session. Actions on the account itself - taking on another client, reaching its settings - are
         * governed by this, not by the capped role that applies inside someone else's data.
         */
        Role homeRole,
        /** The kind of company that owns the login - see {@link com.example.skladdo.model.CompanyType}. */
        CompanyType companyType,
        /**
         * Whether this account operates the platform, so the client knows to offer the admin panel. Purely
         * presentational - every {@code /api/admin} endpoint checks the authority itself, so a tampered
         * profile buys nothing but a link that 403s.
         */
        Boolean platformAdmin,
        /**
         * Storage key of an uploaded profile picture, presigned by the client at render time. Included in
         * listings too - unlike {@code permissions} these are plain columns on the row, so there is no
         * extra query to avoid.
         */
        String avatarKey,
        /** Preset avatar icon name, used when there is no {@code avatarKey}. */
        String avatarIcon,
        /** Palette token for {@code avatarIcon}. */
        String avatarColor,
        /** When this account last signed in, or null if it never has. Shown on the user's profile page. */
        java.time.Instant lastLoginAt,
        /**
         * Date of birth, as the person entered it when they accepted their invitation. Null for accounts
         * created before invitations asked, and for anyone who left it blank.
         */
        java.time.LocalDate birthDate,
        /**
         * The add-ons the company the session is working in currently pays for. The company-level twin of
         * {@code permissions}: both must pass before a gated feature is offered, so the client hides the
         * tender and manufacturer-email pages entirely when the entitlement is absent.
         *
         * <p>Populated for session profiles only ({@code /me}, login, company switch) and left
         * {@code null} in bulk listings - resolving it per row would be one query per user.</p>
         */
        Set<AddonType> addons
) {

    /**
     * The same profile with the company's add-ons attached.
     *
     * <p>Applied by the controller rather than inside the profile builders: those run in a transaction
     * opened before any tenant is bound - at login the token is only being minted - and Hibernate fixes
     * the tenant when it opens the session, so the lookup has to happen out here. See
     * {@code AuthService.addonsOf}.</p>
     */
    public UserDto withAddons(Set<AddonType> addons) {
        return new UserDto(id, email, fullName, role, companyId, companyName, canSeePrices, active,
                archived, passwordSetupPending, emailSignature, language, permissions, warehouseIds,
                homeCompanyId, homeCompanyName, partnerSession, homeRole, companyType, platformAdmin,
                avatarKey, avatarIcon, avatarColor, lastLoginAt, birthDate, addons);
    }
    public static UserDto from(User user) {
        return from(user, null, null);
    }

    public static UserDto from(User user, Map<PermissionModule, ModulePermissionDto> permissions) {
        return from(user, permissions, null);
    }

    public static UserDto from(User user, Map<PermissionModule, ModulePermissionDto> permissions, List<Long> warehouseIds) {
        Company company = user.getCompany();
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                company != null ? company.getId() : null,
                company != null ? company.getName() : null,
                user.getCanSeePrices(),
                user.getActive(),
                user.getArchived(),
                user.getPasswordSetupPending(),
                user.getEmailSignature(),
                user.getLanguage(),
                permissions,
                warehouseIds,
                company != null ? company.getId() : null,
                company != null ? company.getName() : null,
                false,
                user.getRole(),
                company != null ? company.getType() : null,
                user.isPlatformAdmin(),
                user.getAvatarKey(),
                user.getAvatarIcon(),
                user.getAvatarColor(),
                user.getLastLoginAt(),
                user.getBirthDate(),
                null
        );
    }

    public static UserDto fromWithWarehouses(User user, Map<PermissionModule, ModulePermissionDto> permissions) {
        List<Long> warehouseIds = user.getWarehouses().stream()
                .map(Warehouse::getId)
                .sorted()
                .toList();
        return from(user, permissions, warehouseIds);
    }

    /**
     * Session profile for a warehouse operator's user working inside {@code activeCompany}. The account's
     * own role and price flag are deliberately replaced by the capped partner access - what the client
     * granted, not what the user is at home - and {@code warehouseIds} lists only the warehouses the
     * connections cover.
     */
    public static UserDto partnerSession(User user,
                                         Company activeCompany,
                                         boolean canSeePrices,
                                         Map<PermissionModule, ModulePermissionDto> permissions,
                                         List<Long> warehouseIds) {
        Company home = user.getCompany();
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                Role.WAREHOUSE,
                activeCompany.getId(),
                activeCompany.getName(),
                canSeePrices,
                user.getActive(),
                user.getArchived(),
                user.getPasswordSetupPending(),
                user.getEmailSignature(),
                user.getLanguage(),
                permissions,
                warehouseIds,
                home != null ? home.getId() : null,
                home != null ? home.getName() : null,
                true,
                // The real role at home, not the Role.WAREHOUSE cap above: an owner is still the owner of
                // their own account while they happen to be working inside a client's.
                user.getRole(),
                // Always the *home* company's nature: it says what kind of business the account belongs
                // to, which does not change just because they are currently working in a client's data.
                home != null ? home.getType() : null,
                user.isPlatformAdmin(),
                user.getAvatarKey(),
                user.getAvatarIcon(),
                user.getAvatarColor(),
                user.getLastLoginAt(),
                user.getBirthDate(),
                null
        );
    }
}
