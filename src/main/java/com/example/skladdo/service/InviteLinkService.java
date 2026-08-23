package com.example.skladdo.service;

import com.example.skladdo.dto.CreateInviteLinkRequest;
import com.example.skladdo.dto.InviteLinkDto;
import com.example.skladdo.dto.PublicInviteDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.InviteLink;
import com.example.skladdo.model.PlanType;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.InviteLinkRepository;
import com.example.skladdo.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Issues and redeems the operator's invite links - signup URLs that carry their own terms (see
 * {@link InviteLink}).
 *
 * <p>The terms are read from the stored link at signup and never from the request. A visitor holding a
 * link can otherwise simply edit the query string: the plan, the account type and the length of the free
 * period are all things somebody would happily grant themselves.</p>
 */
@Service
public class InviteLinkService {

    private static final Logger log = LoggerFactory.getLogger(InviteLinkService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** No vowels, no 0/O/1/I/L - a code gets read aloud and retyped, so ambiguity costs real support time. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int GROUP = 4;

    private final InviteLinkRepository repository;
    private final CompanyRepository companyRepository;
    private final String frontendBaseUrl;

    public InviteLinkService(InviteLinkRepository repository,
                             CompanyRepository companyRepository,
                             @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    // --- Operator side ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InviteLinkDto> list() {
        Map<Long, Long> signups = companyRepository.countSignupsPerInviteLink().stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));
        return repository.findAllByOrderByIdDesc().stream()
                .map(link -> InviteLinkDto.from(link, urlFor(link), signups.getOrDefault(link.getId(), 0L)))
                .toList();
    }

    @Transactional
    public InviteLinkDto create(CreateInviteLinkRequest request) {
        InviteLink link = new InviteLink();
        link.setCode(generateCode());
        link.setLabel(request.label().trim());
        link.setAccountType(resolveAccountType(request.accountType()));
        link.setPlan(resolvePlan(link.getAccountType(), request.plan()));
        link.setFreeDays(request.freeDays());
        link.setMaxUses(request.maxUses());
        link.setActive(true);
        link.setCreatedAt(Instant.now());
        link.setCreatedByUserId(currentUserIdOrNull());
        if (request.expiresInDays() != null) {
            link.setExpiresAt(Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS));
        }

        InviteLink saved = repository.save(link);
        log.info("Operator issued invite link {} ('{}'): plan={}, freeDays={}, maxUses={}.",
                saved.getCode(), saved.getLabel(), saved.getPlan(), saved.getFreeDays(), saved.getMaxUses());
        return InviteLinkDto.from(saved, urlFor(saved), 0);
    }

    /**
     * Revokes a link. It stays in the table on purpose - companies reference the link they arrived
     * through, and deleting it would erase where they came from.
     */
    @Transactional
    public InviteLinkDto revoke(Long id) {
        InviteLink link = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invite link not found with id: " + id));
        link.setActive(false);
        InviteLink saved = repository.save(link);
        log.info("Operator revoked invite link {} ('{}').", saved.getCode(), saved.getLabel());
        return InviteLinkDto.from(saved, urlFor(saved), signupsFor(saved.getId()));
    }

    /**
     * The account type a link pins signups to, or {@code null} to let the visitor choose.
     *
     * <p>{@link CompanyType#PLATFORM} is refused here specifically. A link's stored type is applied at
     * signup <em>instead of</em> the request's, so it bypasses the check on the public form - a link
     * pinning PLATFORM would mint Skladdo's own operator shell for whoever opened it. This is the check
     * that closes that path.</p>
     */
    private static CompanyType resolveAccountType(String requested) {
        CompanyType type = parse(requested, CompanyType::valueOf);
        if (type != null && !type.isSelectableAtSignup()) {
            throw new BadRequestException("error.invite.invalidTerms");
        }
        return type;
    }

    /**
     * The plan a link pins signups to. A warehouse link never carries one (that account type is free and
     * its plan follows from the type), and a business link may only name a paid tier - otherwise a link
     * could hand out the free warehouse plan to an ordinary company.
     */
    private static PlanType resolvePlan(CompanyType accountType, String requested) {
        PlanType plan = parse(requested, PlanType::valueOf);
        if (plan == null) {
            return null;
        }
        if (accountType == CompanyType.WAREHOUSE || !plan.isSelectable()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        return plan;
    }

    // --- Public side -----------------------------------------------------------------------------

    /**
     * What the signup page may know about a code. Anything wrong with it - unknown, revoked, expired,
     * used up - is reported the same way, since an unauthenticated caller may try any string and none of
     * those distinctions are theirs to learn.
     */
    @Transactional(readOnly = true)
    public PublicInviteDto describe(String code) {
        InviteLink link = findUsable(code);
        if (link == null) {
            return PublicInviteDto.invalid();
        }
        return new PublicInviteDto(true,
                link.getAccountType() == null ? null : link.getAccountType().name(),
                link.getPlan() == null ? null : link.getPlan().name(),
                link.getFreeDays());
    }

    /** The link behind a code if it would still be honoured, else {@code null}. */
    @Transactional(readOnly = true)
    public InviteLink findUsable(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return repository.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .filter(link -> link.isUsable(Instant.now()))
                .orElse(null);
    }

    /**
     * Records that a signup used the link.
     *
     * <p>Not a reservation: two signups racing on the last remaining use can both get in. That is the
     * right trade here - the alternative is locking a row on every public signup to protect a limit whose
     * purpose is "roughly this many", and turning away a real customer who arrived a moment too late is
     * worse than one extra account.</p>
     */
    @Transactional
    public void markUsed(Long linkId) {
        repository.findById(linkId).ifPresent(link -> {
            link.setUsedCount(link.getUsedCount() + 1);
            repository.save(link);
        });
    }

    // --- Plumbing --------------------------------------------------------------------------------

    private long signupsFor(Long linkId) {
        return companyRepository.countByInviteLinkId(linkId);
    }

    private String urlFor(InviteLink link) {
        return frontendBaseUrl + "/register?invite=" + link.getCode();
    }

    private static <T> T parse(String value, Function<String, T> parser) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.invite.invalidTerms");
        }
    }

    private static Long currentUserIdOrNull() {
        try {
            return SecurityUtil.currentUserId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** {@code INV-XXXX-XXXX}, matching the shape of the warehouse connection code. */
    private String generateCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "INV-" + randomGroup() + "-" + randomGroup();
            if (repository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite code");
    }

    private static String randomGroup() {
        StringBuilder out = new StringBuilder(GROUP);
        for (int i = 0; i < GROUP; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
