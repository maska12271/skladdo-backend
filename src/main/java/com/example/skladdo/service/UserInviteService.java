package com.example.skladdo.service;

import com.example.skladdo.dto.AcceptUserInviteRequest;
import com.example.skladdo.dto.CreateUserInviteRequest;
import com.example.skladdo.dto.ModulePermissionDto;
import com.example.skladdo.dto.PublicUserInviteDto;
import com.example.skladdo.dto.SendUserInviteRequest;
import com.example.skladdo.dto.SendUserInviteResult;
import com.example.skladdo.dto.UserInviteDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ForbiddenException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.AuditAction;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.NotificationType;
import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.model.UserInvite;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.UserInviteRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.SecurityUtil;
import com.example.skladdo.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and redeems the links a company uses to bring someone on board.
 *
 * <p>An administrator decides the access and hands over a link; the person on the other end fills in
 * their own details and their account appears. Nothing exists until they do - there is no half-made
 * account sitting in the user list waiting for a password, and a link nobody follows leaves nothing
 * behind but the invitation row.</p>
 *
 * <p><strong>{@link #accept} runs unauthenticated</strong>, with no security context and no bound tenant.
 * It reads the company off the invitation and binds it with {@link TenantContext} before touching
 * anything company-scoped. Like {@code RegistrationService}, it is deliberately <em>not</em>
 * {@code @Transactional} at this level: Hibernate fixes the tenant discriminator when the session opens,
 * so a transaction spanning the binding would have been opened without one. Each collaborator runs in its
 * own transaction underneath, and single use is enforced by
 * {@link UserInviteRepository#claim} rather than by the transaction boundary.</p>
 */
@Service
public class UserInviteService {

    private static final Logger log = LoggerFactory.getLogger(UserInviteService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Accepted profile-picture payload: a base64 image data URI, nothing else. */
    private static final java.util.regex.Pattern AVATAR_DATA_URI =
            java.util.regex.Pattern.compile("^data:image/(jpeg|jpg|png|webp);base64,([A-Za-z0-9+/=\\s]+)$");

    /** 2 MB. The client sends a 512px JPEG (tens of KB); this is the ceiling, not the expectation. */
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    private final UserInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final PlanService planService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CompanySettingsService companySettingsService;
    private final PlatformMailer mailer;
    private final StorageService storageService;

    private final String frontendBaseUrl;
    private final int expiryHours;

    public UserInviteService(UserInviteRepository inviteRepository,
                             UserRepository userRepository,
                             CompanyRepository companyRepository,
                             PasswordEncoder passwordEncoder,
                             PermissionService permissionService,
                             PlanService planService,
                             AuditService auditService,
                             NotificationService notificationService,
                             CompanySettingsService companySettingsService,
                             PlatformMailer mailer,
                             StorageService storageService,
                             @Value("${app.frontend-base-url}") String frontendBaseUrl,
                             @Value("${app.user-invite.expiry-hours:72}") int expiryHours) {
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.planService = planService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.companySettingsService = companySettingsService;
        this.mailer = mailer;
        this.storageService = storageService;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
        this.expiryHours = expiryHours;
    }

    /** How long a freshly minted link stays usable, so the UI can say so without guessing. */
    public int expiryHours() {
        return expiryHours;
    }

    // --- Administrator side -----------------------------------------------------------------------

    /**
     * Mints a link for one future colleague.
     *
     * <p>The seat check runs here <em>and</em> again at redemption: here so an administrator already at
     * their plan's limit is told now rather than sending a link that fails on someone else's screen, and
     * again at redemption because several outstanding links could otherwise take the company past the
     * limit between them.</p>
     */
    @Transactional
    public UserInviteDto create(CreateUserInviteRequest request) {
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("error.userInvite.ownerCannotBeInvited");
        }
        planService.assertCanCreateUser();

        boolean restricted = isRestricted(request.role());
        UserInvite invite = new UserInvite();
        invite.setCompanyId(SecurityUtil.currentCompanyId());
        invite.setToken(generateToken());
        invite.setRole(request.role());
        // Managers always see prices, so the flag only means anything on the restricted roles.
        invite.setCanSeePrices(!restricted || !Boolean.FALSE.equals(request.canSeePrices()));
        invite.setPermissions(encodePermissions(restricted ? request.permissions() : null));
        invite.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        invite.setCreatedAt(Instant.now());
        invite.setCreatedByUserId(SecurityUtil.currentUserId());

        return toDto(inviteRepository.save(invite));
    }

    /** Every invitation this company has issued, newest first - pending, spent, withdrawn and lapsed. */
    @Transactional(readOnly = true)
    public List<UserInviteDto> list() {
        return inviteRepository.findByCompanyIdOrderByIdDesc(SecurityUtil.currentCompanyId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Withdraws an outstanding invitation. Kept as a row rather than deleted, and harmless to repeat -
     * but an invitation already taken up cannot be withdrawn, because the account it produced exists and
     * is removed on the users page like any other.
     */
    @Transactional
    public UserInviteDto revoke(Long id) {
        UserInvite invite = requireSameCompany(id);
        if (invite.isAccepted()) {
            throw new BadRequestException("error.userInvite.alreadyAccepted");
        }
        invite.setRevoked(true);
        return toDto(inviteRepository.save(invite));
    }

    /**
     * Emails an existing link to an address the administrator supplies.
     *
     * <p>Delivery only: the address is recorded so the list can show where the invitation went, but it
     * does not bind the link - whoever opens it enters their own address. Sending is a separate act from
     * minting for the same reason it is on the password-setup flow: plenty of administrators would rather
     * paste the link into a chat they know their colleague reads.</p>
     */
    @Transactional
    public SendUserInviteResult send(Long id, SendUserInviteRequest request) {
        UserInvite invite = requireSameCompany(id);
        if (!invite.isRedeemable(Instant.now())) {
            throw new BadRequestException("error.userInvite.notUsable");
        }
        String recipient = request.email().trim();
        Company company = requireCompany(invite.getCompanyId());

        // No platform sender configured means nothing leaves, and the caller has to be told: the link is
        // still in their hands, but only if they know to use it.
        boolean willSend = mailer.isConfigured();
        if (willSend) {
            // Queued, not sent inline - see PlatformMailer. The locale is read here, on the request thread.
            mailer.sendCompanyInvite(recipient, company.getName(), linkFor(invite), expiryHours,
                    LocaleContextHolder.getLocale());
            // Recorded only when something actually went out, so the pending list never claims an
            // invitation was emailed to somebody who was never written to.
            invite.setSentToEmail(recipient);
            invite.setSentAt(Instant.now());
            invite = inviteRepository.save(invite);
        }
        return new SendUserInviteResult(willSend, toDto(invite));
    }

    // --- Invitee side (unauthenticated) -----------------------------------------------------------

    /** The company behind a token, or {@code valid = false}. Always 200 - a dead link is not an error. */
    @Transactional(readOnly = true)
    public PublicUserInviteDto describe(String token) {
        return inviteRepository.findByToken(token)
                .filter(invite -> invite.isRedeemable(Instant.now()))
                .map(invite -> companyRepository.findById(invite.getCompanyId())
                        .map(company -> new PublicUserInviteDto(true, company.getName(), invite.getExpiresAt()))
                        .orElseGet(PublicUserInviteDto::invalid))
                .orElseGet(PublicUserInviteDto::invalid);
    }

    /**
     * Turns a link into an account, and tells the company about it.
     *
     * <p>Order matters. Everything that can reasonably fail - the address already being taken, the
     * company being out of seats - is checked <em>before</em> the invitation is claimed, so an ordinary
     * mistake leaves the link usable and the person can simply correct it and try again. The claim itself
     * is the point of no return: past it the link is spent whatever happens next, which is the right way
     * round for something whose job is to work exactly once.</p>
     *
     * @return the name of the company just joined, for the confirmation the page shows
     */
    public String accept(AcceptUserInviteRequest request) {
        UserInvite invite = inviteRepository.findByToken(request.token())
                .filter(i -> i.isRedeemable(Instant.now()))
                .orElseThrow(() -> new BadRequestException("error.userInvite.notUsable"));

        // The tenant must be bound before anything company-scoped opens a session - see the class note.
        return TenantContext.callAs(invite.getCompanyId(), () -> createAccountFor(invite, request));
    }

    private String createAccountFor(UserInvite invite, AcceptUserInviteRequest request) {
        // Lowercased like every other account: sign-in and password reset match case-insensitively, so
        // two rows differing only by case would be two accounts nobody could tell apart.
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("error.userInvite.emailExists");
        }
        planService.assertCanCreateUser(invite.getCompanyId());
        Company company = requireCompany(invite.getCompanyId());

        if (inviteRepository.claim(invite.getId(), Instant.now()) == 0) {
            throw new BadRequestException("error.userInvite.notUsable");
        }

        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setBirthDate(request.birthDate());
        user.setRole(invite.getRole());
        user.setCanSeePrices(invite.isCanSeePrices());
        // Unlike the admin-creates-the-account flow there is no pending state to sit in: the password
        // arrives in the same request that creates the row, so the account works immediately.
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordSetupPending(false);
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);
        user.setLanguage(companySettingsService.getOrCreate().getDefaultUserLanguage());
        user.setAvatarKey(storeAvatar(request.avatarImage()));

        User saved = userRepository.save(user);
        if (isRestricted(saved.getRole())) {
            List<ModulePermissionDto> overrides = decodePermissions(invite.getPermissions());
            if (overrides.isEmpty()) {
                permissionService.applyDefaultPermissions(saved);
            } else {
                permissionService.replacePermissions(saved, overrides);
            }
        }

        inviteRepository.findById(invite.getId()).ifPresent(claimed -> {
            claimed.setAcceptedUserId(saved.getId());
            inviteRepository.save(claimed);
        });

        // No actor on this row: nobody was signed in. The trail still names the account that appeared.
        auditService.record(AuditService.ENTITY_USER, saved.getId(), AuditAction.CREATE, saved.getEmail());
        notificationService.notifyManagers(
                NotificationType.USER_JOINED,
                displayName(saved),
                "/users/" + saved.getId(),
                "user-joined:" + saved.getId());
        return company.getName();
    }

    /**
     * Turns the invitee's {@code data:image/...;base64,...} photo into a stored object, or {@code null}.
     *
     * <p>Anything it cannot make sense of is dropped rather than raised: the photo is the least important
     * thing in this request, and failing an account creation over a malformed avatar would be a poor
     * trade. Capped at {@link #MAX_AVATAR_BYTES} because this endpoint is unauthenticated - the token
     * limits who can post here, but not how much they can post - and the client only ever sends a
     * 512px square, which is comfortably inside it.</p>
     */
    private String storeAvatar(String dataUri) {
        if (dataUri == null || dataUri.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = AVATAR_DATA_URI.matcher(dataUri.trim());
        if (!matcher.matches()) {
            log.warn("Ignoring an invitation avatar that is not a base64 image data URI.");
            return null;
        }
        String contentType = "image/" + matcher.group(1).toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring an invitation avatar that is not valid base64.");
            return null;
        }
        if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
            log.warn("Ignoring an invitation avatar of {} bytes (cap {}).", bytes.length, MAX_AVATAR_BYTES);
            return null;
        }
        try {
            return storageService.store(bytes, contentType, extensionFor(matcher.group(1)), "images");
        } catch (Exception e) {
            log.warn("Could not store an invitation avatar: {}", e.getMessage());
            return null;
        }
    }

    private static String extensionFor(String subtype) {
        return switch (subtype.toLowerCase(Locale.ROOT)) {
            case "png" -> ".png";
            case "webp" -> ".webp";
            default -> ".jpg";
        };
    }

    // --- Internals --------------------------------------------------------------------------------

    private UserInvite requireSameCompany(Long id) {
        return inviteRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found with id: " + id));
    }

    private Company requireCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));
    }

    private UserInviteDto toDto(UserInvite invite) {
        String acceptedEmail = Optional.ofNullable(invite.getAcceptedUserId())
                .flatMap(userRepository::findById)
                .map(User::getEmail)
                .orElse(null);
        return UserInviteDto.from(invite, linkFor(invite), acceptedEmail);
    }

    private String linkFor(UserInvite invite) {
        return frontendBaseUrl + "/join?token=" + invite.getToken();
    }

    private static String displayName(User user) {
        return user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : user.getEmail();
    }

    private static boolean isRestricted(Role role) {
        return role == Role.USER || role == Role.WAREHOUSE;
    }

    /**
     * Packs the chosen access into one column as {@code MODULE=VCED}, comma separated, where each letter
     * position is 1 or 0. Modules with no access at all are left out, so the common "view a few things"
     * grant stays short and the column stays readable in a database client.
     *
     * <p>Returns {@code null} for "nothing was chosen", which redemption reads as "use the company's own
     * default template" - the same thing creating a user directly does.</p>
     */
    static String encodePermissions(List<ModulePermissionDto> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (ModulePermissionDto dto : permissions) {
            if (dto == null || dto.module() == null) {
                continue;
            }
            if (!dto.canView() && !dto.canCreate() && !dto.canEdit() && !dto.canDelete()) {
                continue;
            }
            parts.add(dto.module().name() + "="
                    + bit(dto.canView()) + bit(dto.canCreate()) + bit(dto.canEdit()) + bit(dto.canDelete()));
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /**
     * The inverse of {@link #encodePermissions}. Unknown module names are skipped rather than thrown on:
     * a module can be removed from the enum while a three-day-old invitation still names it, and losing
     * one line of a grant is a better outcome than the whole redemption failing.
     */
    static List<ModulePermissionDto> decodePermissions(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        Map<String, PermissionModule> known = new java.util.HashMap<>();
        for (PermissionModule module : PermissionModule.values()) {
            known.put(module.name(), module);
        }
        // Keyed by module so a malformed invitation naming one twice cannot produce two rows for it.
        Map<PermissionModule, ModulePermissionDto> result = new EnumMap<>(PermissionModule.class);
        for (String part : encoded.split(",")) {
            String[] halves = part.trim().split("=", 2);
            if (halves.length != 2 || halves[1].length() != 4) {
                continue;
            }
            PermissionModule module = known.get(halves[0].trim());
            if (module == null) {
                continue;
            }
            String flags = halves[1];
            result.put(module, new ModulePermissionDto(module,
                    flags.charAt(0) == '1', flags.charAt(1) == '1',
                    flags.charAt(2) == '1', flags.charAt(3) == '1'));
        }
        return List.copyOf(result.values());
    }

    private static char bit(boolean value) {
        return value ? '1' : '0';
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
