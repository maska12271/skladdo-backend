package com.example.skladdo.service;

import com.example.skladdo.dto.CompanyOptionDto;
import com.example.skladdo.dto.LoginRequest;
import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.UserDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ForbiddenException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.ConnectionStatus;
import com.example.skladdo.model.User;
import com.example.skladdo.model.WarehouseConnection;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.repository.WarehouseConnectionRepository;
import com.example.skladdo.security.CustomUserDetails;
import com.example.skladdo.security.JwtService;
import com.example.skladdo.security.SecurityUtil;
import com.example.skladdo.security.TenantContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final CompanyRepository companyRepository;
    private final WarehouseConnectionRepository connectionRepository;
    private final PlanService planService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       UserRepository userRepository,
                       PermissionService permissionService,
                       PasswordEncoder passwordEncoder,
                       CompanyRepository companyRepository,
                       WarehouseConnectionRepository connectionRepository,
                       PlanService planService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.companyRepository = companyRepository;
        this.connectionRepository = connectionRepository;
        this.planService = planService;
    }

    /**
     * The add-ons {@code companyId} currently pays for - the company-level entitlements that decide whether
     * the tender and manufacturer-email features are offered at all.
     *
     * <p>Called by {@code AuthController} on the way out rather than from inside the profile builders, and
     * that placement is load-bearing twice over. The builders run in a transaction opened <em>before</em>
     * any tenant is bound (at login the token is only being minted now), and Hibernate fixes the tenant id
     * when it opens the session - so the binding has to be established out here, before the call, exactly
     * as the nightly sweep does it.</p>
     */
    public Set<com.example.skladdo.model.AddonType> addonsOf(Long companyId) {
        if (companyId == null) {
            return Set.of();
        }
        return TenantContext.callAs(companyId, planService::activeAddons);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Tell the user precisely which field to fix — unknown email vs. wrong password. This
        // deliberately trades away login user-enumeration resistance for clearer feedback.
        // A retired account reads as no account at all. Its row survives only so old records can still
        // name the person; there is nothing here to sign in to.
        User account = userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BadRequestException("error.auth.noAccount"));

        // A freshly-invited account has no usable password yet: surface the actionable "set your
        // password first" message instead of a bare "wrong password" (which its random placeholder
        // hash would otherwise produce).
        if (Boolean.TRUE.equals(account.getPasswordSetupPending())) {
            throw new BadRequestException("error.auth.passwordSetupPending");
        }

        // A suspended company would otherwise fail authentication as a disabled account, which surfaces as
        // a bare credentials error and sends the user hunting for a password that is not the problem. The
        // operator is exempt for the same reason as CustomUserDetails.isEnabled(): they must still be able
        // to reach the panel that lifts the suspension.
        if (account.getCompany() != null && !account.getCompany().isActive() && !account.isPlatformAdmin()) {
            throw new BadRequestException("error.auth.companySuspended");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            // The email exists and its password is set, so a credentials failure means a wrong password.
            throw new BadRequestException("error.auth.wrongPassword");
        }

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();

        // A warehouse account has no company of its own to work in, so signing in "to itself" would land
        // on an empty shell. Open the first client instead - the session it would otherwise have to reach
        // through the company picker anyway. With no clients yet there is nothing to open, and the plain
        // session (settings, users, connections) is the right answer.
        //
        // Deliberately here rather than in issueSession: switching *back* to your own account also mints a
        // session, and doing this there would make going home impossible.
        LoginResponse partner = firstClientSession(account);
        return partner != null ? partner : issueSession(userDetails.getId());
    }

    /**
     * Records that an account has just signed in, for the platform admin panel's "companies active in the
     * last N months" figure. Best-effort on purpose: this is bookkeeping, and a user who has authenticated
     * correctly must not be turned away because a statistics column could not be written.
     */
    public void recordLogin(Long userId) {
        try {
            userRepository.recordLogin(userId, java.time.Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not record the sign-in time for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Mints a JWT for the given account and returns it alongside the user's profile — the same payload a
     * successful login yields. Shared by {@link #login} and the self-service signup flow (which logs the
     * new owner straight in). Runs in its own read transaction so the lazy warehouse collection resolves.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public LoginResponse issueSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.getWarehouses().size(); // init lazy collection for inclusion in response
        String token = jwtService.generateToken(new CustomUserDetails(user));
        return new LoginResponse(token, UserDto.fromWithWarehouses(user, permissionService.permissionMapFor(user)));
    }

    /**
     * A session already pinned to the first company this warehouse account works for, or {@code null} when
     * that does not apply. Ordered by company name so "first" is stable rather than whichever row the
     * database happened to return.
     */
    private LoginResponse firstClientSession(User user) {
        Company home = user.getCompany();
        if (home == null || !home.isWarehouseAccount()) {
            return null;
        }
        List<WarehouseConnection> live =
                connectionRepository.findByWarehouseCompanyIdAndStatus(home.getId(), ConnectionStatus.ACTIVE);
        if (live.isEmpty()) {
            return null;
        }
        Map<Long, Company> clients = new java.util.HashMap<>();
        companyRepository.findAllById(live.stream().map(WarehouseConnection::getClientCompanyId).toList())
                .forEach(company -> clients.put(company.getId(), company));

        return live.stream()
                .filter(connection -> clients.containsKey(connection.getClientCompanyId()))
                .min(Comparator.comparing(
                        connection -> clients.get(connection.getClientCompanyId()).getName(),
                        String.CASE_INSENSITIVE_ORDER))
                .map(connection -> {
                    Company client = clients.get(connection.getClientCompanyId());
                    CustomUserDetails acting = new CustomUserDetails(user)
                            .actingIn(client.getId(), connection.isCanSeePrices());
                    String token = jwtService.generateToken(acting, client.getId());
                    return new LoginResponse(token,
                            partnerProfile(user, client, connection.isCanSeePrices(), connection));
                })
                .orElse(null);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserDto currentUser() {
        return profile(requireCurrentUser());
    }

    // ---------------------------------------------------------------------------------------------
    // Working in another company (warehouse operators)
    // ---------------------------------------------------------------------------------------------

    /**
     * The companies this session may work in: the account's own, plus every client company reachable
     * through an active warehouse connection. One entry for an ordinary user, which is how the client
     * knows not to render a switcher at all.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<CompanyOptionDto> availableCompanies() {
        CustomUserDetails principal = SecurityUtil.currentUser();
        Long homeId = principal.getHomeCompanyId();
        Long activeId = principal.getCompanyId();

        Company home = companyRepository.findById(homeId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + homeId));

        List<CompanyOptionDto> options = new ArrayList<>();
        options.add(new CompanyOptionDto(home.getId(), home.getName(),
                true, home.getId().equals(activeId)));

        Set<Long> clientIds = new LinkedHashSet<>();
        for (WarehouseConnection connection :
                connectionRepository.findByWarehouseCompanyIdAndStatus(homeId, ConnectionStatus.ACTIVE)) {
            clientIds.add(connection.getClientCompanyId());
        }
        if (!clientIds.isEmpty()) {
            companyRepository.findAllById(clientIds).stream()
                    .sorted(Comparator.comparing(Company::getName, String.CASE_INSENSITIVE_ORDER))
                    .forEach(client -> options.add(new CompanyOptionDto(
                            client.getId(), client.getName(),
                            false, client.getId().equals(activeId))));
        }
        return options;
    }

    /**
     * Issues a session pinned to another company the caller may work in - the "change company fast"
     * action. Passing the account's own company id switches back home.
     *
     * <p>The new token only <em>asks</em> to act in that company; {@code JwtAuthenticationFilter} re-checks
     * the connection on every request, so this method being the gate is a convenience, not the security
     * boundary.</p>
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public LoginResponse switchCompany(Long companyId) {
        CustomUserDetails principal = SecurityUtil.currentUser();
        User user = requireCurrentUser();
        Long homeId = principal.getHomeCompanyId();

        if (companyId == null || companyId.equals(homeId)) {
            return issueSession(user.getId());
        }

        WarehouseConnection live = connectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(homeId, companyId, ConnectionStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenException("error.partner.noAccess"));
        Company active = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        boolean canSeePrices = live.isCanSeePrices();
        CustomUserDetails acting = new CustomUserDetails(user).actingIn(companyId, canSeePrices);
        String token = jwtService.generateToken(acting, companyId);
        return new LoginResponse(token, partnerProfile(user, active, canSeePrices, live));
    }

    /**
     * The session profile for a user, shaped by the company they are currently working in. Every endpoint
     * that hands the client back "who am I" goes through here, so editing your own name or signature
     * mid-switch cannot silently drop you back into your own company's view.
     */
    private UserDto profile(User user) {
        CustomUserDetails principal = SecurityUtil.currentUser();
        if (!principal.isPartnerSession()) {
            user.getWarehouses().size(); // init lazy collection for inclusion in response
            return UserDto.fromWithWarehouses(user, permissionService.permissionMapFor(user));
        }
        Long activeId = principal.getCompanyId();
        Company active = companyRepository.findById(activeId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + activeId));
        WarehouseConnection live = connectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
                        principal.getHomeCompanyId(), activeId, ConnectionStatus.ACTIVE)
                .orElse(null);
        return partnerProfile(user, active, principal.isPartnerCanSeePrices(), live);
    }

    /** Profile of a user acting inside a client company: capped access, and only the assigned warehouses. */
    private UserDto partnerProfile(User user, Company active, boolean canSeePrices,
                                   WarehouseConnection connection) {
        List<Long> warehouseIds = connection == null
                ? List.of()
                : connection.getWarehouseIds().stream().sorted().toList();
        return UserDto.partnerSession(user, active, canSeePrices,
                permissionService.partnerPermissionMap(), warehouseIds);
    }

    private User requireCurrentUser() {
        return userRepository.findById(SecurityUtil.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * Updates the signed-in user's own avatar - an uploaded picture or a preset icon - and returns the
     * refreshed profile. Reuses {@code UserService.applyAvatar} so "a photo replaces an icon, and clearing
     * one clears the other" is decided in exactly one place, whoever is doing the setting.
     */
    @org.springframework.transaction.annotation.Transactional
    public UserDto updateAvatar(String avatarKey, String avatarIcon, String avatarColor) {
        User user = requireCurrentUser();
        UserService.applyAvatar(user, avatarKey, avatarIcon, avatarColor);
        userRepository.save(user);
        return profile(user);
    }

    /** Updates the signed-in user's own email signature and returns the refreshed profile. */
    @org.springframework.transaction.annotation.Transactional
    public UserDto updateSignature(String signature) {
        User user = requireCurrentUser();
        user.setEmailSignature(signature != null && signature.isBlank() ? null : signature);
        userRepository.save(user);
        return profile(user);
    }

    /**
     * Updates the signed-in user's own display name and interface language, and returns the refreshed
     * profile. Each field is only touched when supplied, so the account page can save one section at a
     * time; an unrecognised language is ignored rather than stored.
     */
    @org.springframework.transaction.annotation.Transactional
    public UserDto updateProfile(String fullName, String language) {
        User user = requireCurrentUser();
        if (fullName != null) {
            String trimmed = fullName.trim();
            user.setFullName(trimmed.isEmpty() ? null : trimmed);
        }
        if (language != null && CompanySettings.SUPPORTED_LANGUAGES.contains(language)) {
            user.setLanguage(language);
        }
        userRepository.save(user);
        return profile(user);
    }

    /**
     * Changes the signed-in user's own password after verifying their current one. Rejects a wrong current
     * password ({@code error.auth.wrongPassword}) or a too-short new password ({@code error.user.passwordTooShort})
     * - the same minimum enforced on the reset flow.
     */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(String currentPassword, String newPassword) {
        User user = requireCurrentUser();
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("error.auth.wrongPassword");
        }
        if (newPassword == null || newPassword.length() < com.example.skladdo.security.PasswordPolicy.MIN_LENGTH) {
            throw new BadRequestException("error.user.passwordTooShort");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
