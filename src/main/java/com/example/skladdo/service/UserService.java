package com.example.skladdo.service;

import com.example.skladdo.dto.CreateUserRequest;
import com.example.skladdo.dto.CreatedUserResponse;
import com.example.skladdo.dto.ModulePermissionDto;
import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.dto.UpdatePermissionsRequest;
import com.example.skladdo.dto.UpdateUserRequest;
import com.example.skladdo.dto.UpdateUserWarehousesRequest;
import com.example.skladdo.dto.UserDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ForbiddenException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.AuditAction;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.SecurityUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Manages user accounts within the caller's company. All lookups are scoped to the current
 * company so administrators can never read or modify another company's users.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final WarehouseService warehouseService;
    private final PasswordResetService passwordResetService;
    private final PlanService planService;
    private final AuditService auditService;
    private final CompanySettingsService companySettingsService;

    public UserService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       PermissionService permissionService,
                       WarehouseService warehouseService,
                       PasswordResetService passwordResetService,
                       PlanService planService,
                       AuditService auditService,
                       CompanySettingsService companySettingsService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.warehouseService = warehouseService;
        this.passwordResetService = passwordResetService;
        this.planService = planService;
        this.auditService = auditService;
        this.companySettingsService = companySettingsService;
    }

    public List<UserDto> findAllForCurrentCompany() {
        return userRepository.findByCompanyIdOrderByIdDesc(SecurityUtil.currentCompanyId())
                .stream()
                .map(UserDto::from)
                .toList();
    }

    /** Single user, scoped to the caller's company. Includes warehouse assignment. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserDto findOne(Long id) {
        User user = requireSameCompany(id);
        user.getWarehouses().size(); // init lazy collection
        return UserDto.from(user, null,
                user.getWarehouses().stream()
                        .map(w -> w.getId())
                        .sorted()
                        .toList());
    }

    public CreatedUserResponse create(CreateUserRequest request) {
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotCreate");
        }
        // Stored lowercased so sign-in and password-reset lookups, which match case-insensitively, can
        // never find two accounts differing only by case.
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("error.user.emailExists");
        }
        // Seats are capped by the company's plan; existing accounts are never affected, only new ones.
        planService.assertCanCreateUser();

        Long companyId = SecurityUtil.currentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName());
        user.setRole(request.role());
        user.setCanSeePrices(request.canSeePrices() == null ? Boolean.TRUE : request.canSeePrices());
        applyAvatar(user, request.avatarKey(), request.avatarIcon(), request.avatarColor());
        // Created without a usable password: a random hash keeps the NOT NULL column satisfied while
        // being impossible to guess, and the pending flag blocks login until the user sets their own.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPasswordSetupPending(true);
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);
        // Start the account in the company's configured language; they can change it in My Account.
        user.setLanguage(companySettingsService.getOrCreate().getDefaultUserLanguage());

        User saved = userRepository.save(user);
        // A brand-new restricted account (regular user or warehouse) starts with its default access
        // instead of nothing.
        if (isRestricted(saved.getRole())) {
            permissionService.applyDefaultPermissions(saved);
        }
        // The invitation link is minted but NOT sent: the account exists the moment this returns, and how
        // the colleague hears about it is then the administrator's choice - emailed from here, or copied
        // and passed on themselves. `POST /users/{id}/setup-email` is the send half.
        auditService.record(AuditService.ENTITY_USER, saved.getId(), AuditAction.CREATE, saved.getEmail());
        SetupLinkResponse invite = passwordResetService.issueForUser(saved, false);
        return new CreatedUserResponse(UserDto.from(saved), invite.emailSent(), invite.setupLink(), invite.expiresAt());
    }

    public UserDto update(Long id, UpdateUserRequest request) {
        User user = requireSameCompany(id);
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotModify");
        }
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("error.user.cannotPromoteOwner");
        }

        boolean wasRestricted = isRestricted(user.getRole());

        user.setFullName(request.fullName());
        user.setRole(request.role());
        if (request.canSeePrices() != null) {
            user.setCanSeePrices(request.canSeePrices());
        }
        applyAvatar(user, request.avatarKey(), request.avatarIcon(), request.avatarColor());

        boolean nowRestricted = isRestricted(request.role());
        User saved = userRepository.save(user);
        // Keep permission rows consistent with the role: a fresh move into a restricted role (user or
        // warehouse) gets the defaults, a promotion to a manager role drops the now-ignored rows.
        // Switching between the two restricted roles keeps the existing rows untouched.
        if (nowRestricted && !wasRestricted) {
            permissionService.applyDefaultPermissions(saved);
        } else if (!nowRestricted && wasRestricted) {
            permissionService.clearPermissions(saved.getId());
        }
        auditService.record(AuditService.ENTITY_USER, saved.getId(), AuditAction.UPDATE, saved.getEmail());
        return UserDto.from(saved);
    }

    /**
     * Writes an avatar onto {@code user}: either an uploaded picture or a preset icon, never both.
     *
     * <p>The upload wins when both arrive, and whichever loses is cleared - otherwise an account that
     * switched from a photo to an icon would keep the photo in the column and go on showing it, since the
     * key is what the client renders first.</p>
     */
    static void applyAvatar(User user, String avatarKey, String avatarIcon, String avatarColor) {
        String key = blankToNull(avatarKey);
        if (key != null) {
            user.setAvatarKey(key);
            user.setAvatarIcon(null);
            user.setAvatarColor(null);
            return;
        }
        user.setAvatarKey(null);
        user.setAvatarIcon(blankToNull(avatarIcon));
        user.setAvatarColor(blankToNull(avatarColor));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Permanently retires an account.
     *
     * <p>The row is kept and stamped {@code deletedAt} rather than removed. Half the application records
     * who created or last touched something by user id, and those columns are plain ids with no foreign
     * key behind them - so actually deleting the row would not fail, it would quietly turn every one of
     * those references into a blank. Keeping it means the audit trail still names a person; everything
     * else behaves as though the account is gone.</p>
     *
     * <p>Its permissions, warehouse assignments and any outstanding password-setup link go for real: they
     * grant access, and a retired account must not carry any. That also removes the rows that genuinely do
     * hold a foreign key to the user, which is what used to make deleting fail with "this record is still
     * used by other data" for any account that had ever been sent an invitation.</p>
     *
     * <p>One-way on purpose. There is no un-delete, which is what separates this from archiving.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        User user = requireSameCompanyIncludingDeleted(id);
        guardNotSelf(user, "error.user.cannotDeleteSelf");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotDelete");
        }
        if (user.isDeleted()) {
            return;
        }

        permissionService.clearPermissions(user.getId());
        user.getWarehouses().clear();
        passwordResetService.revokeTokensFor(user.getId());

        // Recorded before the address is released, so the trail keeps the real one.
        String originalEmail = user.getEmail();
        auditService.record(AuditService.ENTITY_USER, user.getId(), AuditAction.DELETE, originalEmail);

        user.setDeletedAt(java.time.Instant.now());
        // Belt and braces for every path that asks "may this account act?" without knowing about deletion.
        user.setActive(false);
        user.setArchived(true);
        // Frees the address for re-use. The column is unique, so a retired row holding on to it would make
        // the same person impossible to invite back - which is a strange thing for "deleted" to mean. The
        // tombstone is unique by id and sits on a reserved TLD (RFC 2606), so it can never collide and can
        // never be a real inbox; the address it replaces is in the audit entry written just above.
        user.setEmail(tombstoneEmail(user.getId()));
        userRepository.save(user);
    }

    /**
     * Issues (or re-issues) a password setup/reset link for a user in the caller's company and emails it.
     * Used both to re-invite a pending user and to send an existing user a reset link - it never changes
     * the account's password, so an active user keeps their current one until they actually reset.
     * Returns the link and whether the email was sent (the copyable link is the fallback when the
     * platform SMTP sender is off).
     */
    public SetupLinkResponse sendSetupEmail(Long id) {
        User user = requireSameCompany(id);
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotModify");
        }
        return passwordResetService.issueForUser(user);
    }

    /**
     * Returns the full per-module permission set for a user. Owners and administrators are
     * unrestricted, so every module is reported as full access.
     */
    public List<ModulePermissionDto> getPermissions(Long id) {
        User user = requireSameCompany(id);
        if (!isRestricted(user.getRole())) {
            return Arrays.stream(PermissionModule.values()).map(ModulePermissionDto::all).toList();
        }
        return permissionService.permissionsFor(user.getId());
    }

    /**
     * Overwrites a user's module permissions. Only {@link Role#USER} accounts can be restricted -
     * owners and administrators are always full-access.
     */
    public List<ModulePermissionDto> updatePermissions(Long id, UpdatePermissionsRequest request) {
        User user = requireSameCompany(id);
        if (!isRestricted(user.getRole())) {
            throw new ForbiddenException("error.user.permissionsRestrictedRoles");
        }
        permissionService.replacePermissions(user, request.permissions());
        auditService.record(AuditService.ENTITY_USER, user.getId(), AuditAction.PERMISSIONS_CHANGE, user.getEmail());
        return permissionService.permissionsFor(user.getId());
    }

    public UserDto setArchived(Long id, boolean archived) {
        User user = requireSameCompany(id);
        guardNotSelf(user, archived ? "error.user.cannotArchiveSelf" : "error.user.cannotUnarchiveSelf");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotArchive");
        }
        user.setArchived(archived);
        User saved = userRepository.save(user);
        auditService.record(AuditService.ENTITY_USER, saved.getId(),
                archived ? AuditAction.ARCHIVE : AuditAction.UNARCHIVE, saved.getEmail());
        return UserDto.from(saved);
    }

    /** Replaces the set of warehouses assigned to a user. */
    public List<Long> updateWarehouses(Long id, UpdateUserWarehousesRequest request) {
        requireSameCompany(id); // scope check
        return warehouseService.setUserWarehouses(id, request.warehouseIds());
    }

    /** Restricted roles are governed by per-module permission rows (unlike full-access managers). */
    private static boolean isRestricted(Role role) {
        return role == Role.USER || role == Role.WAREHOUSE;
    }

    /**
     * The user with this id inside the caller's company, or 404.
     *
     * <p>A retired account reads as absent here, which is what closes every write path against it at once -
     * editing, archiving, permissions, re-inviting. Its row exists only so old records can still name it,
     * and that is not a reason to let anyone act on it again. {@link #delete} looks it up separately so
     * deleting twice stays harmless rather than 404ing.</p>
     */
    private User requireSameCompany(Long id) {
        return userRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    /**
     * The address a retired account keeps instead of the one it gave up. Unique per account and
     * undeliverable by construction - {@code .invalid} is reserved precisely so it can never resolve.
     */
    static String tombstoneEmail(Long userId) {
        return "deleted+" + userId + "@removed.invalid";
    }

    /** As above but tolerating an already-retired account, so {@link #delete} is idempotent. */
    private User requireSameCompanyIncludingDeleted(Long id) {
        return userRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    private void guardNotSelf(User user, String messageKey) {
        if (user.getId().equals(SecurityUtil.currentUserId())) {
            throw new ForbiddenException(messageKey);
        }
    }
}
