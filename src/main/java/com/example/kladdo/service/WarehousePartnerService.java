package com.example.kladdo.service;

import com.example.kladdo.dto.ConnectCodeDto;
import com.example.kladdo.dto.RedeemConnectionCodeRequest;
import com.example.kladdo.dto.WarehouseConnectionDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ForbiddenException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.AuditAction;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.ConnectionCode;
import com.example.kladdo.model.ConnectionStatus;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.model.WarehouseConnection;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.ConnectionCodeRepository;
import com.example.kladdo.repository.WarehouseConnectionRepository;
import com.example.kladdo.repository.WarehouseRepository;
import com.example.kladdo.security.SecurityUtil;
import com.example.kladdo.security.TenantContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connections between a warehouse account and the business companies it works for: issuing the code a
 * client hands out, redeeming it, choosing which warehouses the partner may touch, and ending the link.
 *
 * <p><strong>One direction only.</strong> The client company issues a {@link ConnectionCode}; the
 * warehouse company redeems it and is connected immediately. Handing the code over <em>is</em> the
 * client's consent, so there is no invitation from the other side, no preview, and nothing to accept -
 * which is also why the code is short-lived and single-use ({@link #issueCode}).</p>
 *
 * <p>The account type is enforced here, not merely reflected in the UI: only a
 * {@link com.example.kladdo.model.CompanyType#BUSINESS} company can issue a code, and only a
 * {@link com.example.kladdo.model.CompanyType#WAREHOUSE} company can redeem one. A business company can
 * therefore never end up as the partner on a connection.</p>
 *
 * <p><strong>Deliberately not {@code @Transactional} at this level.</strong> Almost every operation here
 * touches two tenants, and the {@code @TenantId} discriminator is fixed when the Hibernate session opens -
 * so reading or writing the other company's data means binding {@link TenantContext} and letting a
 * <em>fresh</em> session pick it up. A transaction spanning the method would pin one tenant for the whole
 * thing. Same reasoning as {@link RegistrationService} and the public password flow.</p>
 */
@Service
public class WarehousePartnerService {

    /** Unambiguous alphabet for codes people read aloud and retype: no I/O/0/1. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_ATTEMPTS = 10;

    private final WarehouseConnectionRepository connectionRepository;
    private final ConnectionCodeRepository codeRepository;
    private final WarehouseRepository warehouseRepository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;

    public WarehousePartnerService(WarehouseConnectionRepository connectionRepository,
                                   ConnectionCodeRepository codeRepository,
                                   WarehouseRepository warehouseRepository,
                                   CompanyRepository companyRepository,
                                   AuditService auditService) {
        this.connectionRepository = connectionRepository;
        this.codeRepository = codeRepository;
        this.warehouseRepository = warehouseRepository;
        this.companyRepository = companyRepository;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------------------------------------
    // The code (client side)
    // ---------------------------------------------------------------------------------------------

    /**
     * The client company's current invitation code, issuing one if there is none that still works.
     * Reissuing abandons whatever came before, so a code that was shared with the wrong person stops
     * working the moment a new one is drawn.
     */
    public ConnectCodeDto currentCode() {
        Company company = requireBusinessCompany();
        Instant now = Instant.now();
        return codeRepository.findByClientCompanyIdAndRedeemedAtIsNullOrderByIdDesc(company.getId()).stream()
                .filter(code -> code.isUsable(now))
                .findFirst()
                .map(code -> toDto(code, company))
                .orElseGet(this::issueCode);
    }

    /**
     * Draws a fresh code and abandons every earlier unredeemed one, so there is only ever a single code
     * outstanding. Expires after {@link ConnectionCode#VALID_HOURS} hours and is spent on first use.
     */
    public ConnectCodeDto issueCode() {
        Company company = requireBusinessCompany();
        Instant now = Instant.now();

        // Abandoning the old codes is the point of reissuing - otherwise "regenerate" would widen access
        // rather than replace it.
        List<ConnectionCode> outstanding =
                codeRepository.findByClientCompanyIdAndRedeemedAtIsNullOrderByIdDesc(company.getId());
        for (ConnectionCode old : outstanding) {
            old.setExpiresAt(now);
        }
        codeRepository.saveAll(outstanding);

        ConnectionCode code = new ConnectionCode();
        code.setClientCompanyId(company.getId());
        code.setCode(allocateCode());
        code.setCreatedAt(now);
        code.setExpiresAt(now.plus(Duration.ofHours(ConnectionCode.VALID_HOURS)));
        code.setCreatedByUserId(SecurityUtil.currentUserId());
        return toDto(codeRepository.save(code), company);
    }

    // ---------------------------------------------------------------------------------------------
    // Redeeming (warehouse side)
    // ---------------------------------------------------------------------------------------------

    /**
     * Joins the client company that issued this code. Because the code is the consent, the connection is
     * live as soon as this returns - though it grants no stock until the client assigns warehouses to it
     * ({@link #setWarehouses}).
     */
    public WarehouseConnectionDto redeem(RedeemConnectionCodeRequest request) {
        Company warehouseCompany = requireWarehouseCompany();
        Instant now = Instant.now();

        ConnectionCode code = codeRepository.findByCode(normalise(request.code()))
                .orElseThrow(() -> new BadRequestException("error.partner.unknownCode"));
        if (code.isRedeemed()) {
            throw new BadRequestException("error.partner.codeUsed");
        }
        if (code.isExpired(now)) {
            throw new BadRequestException("error.partner.codeExpired");
        }
        if (code.getClientCompanyId().equals(warehouseCompany.getId())) {
            throw new BadRequestException("error.partner.ownCompany");
        }
        connectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
                        warehouseCompany.getId(), code.getClientCompanyId(), ConnectionStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new BadRequestException("error.partner.alreadyConnected");
                });

        WarehouseConnection connection = new WarehouseConnection();
        connection.setWarehouseCompanyId(warehouseCompany.getId());
        connection.setClientCompanyId(code.getClientCompanyId());
        connection.setStatus(ConnectionStatus.ACTIVE);
        connection.setCreatedAt(now);
        WarehouseConnection saved = connectionRepository.save(connection);

        // Spending the code only after the connection exists: a retry of a failed redeem should still work.
        code.setRedeemedAt(now);
        code.setRedeemedByCompanyId(warehouseCompany.getId());
        codeRepository.save(code);

        recordOnBothSides(saved, AuditAction.CREATE, "CONNECTED");
        return toDto(saved, warehouseCompany.getId());
    }

    // ---------------------------------------------------------------------------------------------
    // Client-side controls
    // ---------------------------------------------------------------------------------------------

    /**
     * Chooses which of the client's own warehouses this partner may work in. The client's choice is the
     * whole access scope - a partner sees nothing company-wide, only what is listed here - so it is theirs
     * alone to set, and clearing the list shuts the partner out without ending the connection.
     */
    public WarehouseConnectionDto setWarehouses(Long connectionId, List<Long> warehouseIds) {
        Long companyId = currentCompanyId();
        WarehouseConnection connection = requireParty(connectionId, companyId);
        requireClientSide(connection, companyId);

        Set<Long> desired = new LinkedHashSet<>();
        if (warehouseIds != null) {
            for (Long warehouseId : warehouseIds) {
                // Resolved against the client's own tenant, so an id from another company simply is not found.
                warehouseRepository.findById(warehouseId)
                        .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + warehouseId));
                desired.add(warehouseId);
            }
        }
        connection.setWarehouseIds(desired);
        WarehouseConnection saved = connectionRepository.save(connection);
        recordOnBothSides(saved, AuditAction.UPDATE, "WAREHOUSES_" + desired.size());
        return toDto(saved, companyId);
    }

    /**
     * The client company's other setting: whether the partner's staff see monetary values. It is their
     * commercial data, so it is not the partner's switch to flip.
     */
    public WarehouseConnectionDto setCanSeePrices(Long connectionId, boolean canSeePrices) {
        Long companyId = currentCompanyId();
        WarehouseConnection connection = requireParty(connectionId, companyId);
        requireClientSide(connection, companyId);
        connection.setCanSeePrices(canSeePrices);
        WarehouseConnection saved = connectionRepository.save(connection);
        recordOnBothSides(saved, AuditAction.UPDATE, canSeePrices ? "PRICES_SHOWN" : "PRICES_HIDDEN");
        return toDto(saved, companyId);
    }

    /**
     * Ends a connection from either side. Access stops on the next request (the filter re-checks the
     * connection every time) and nothing else changes: the warehouses were always the client's, and so is
     * everything booked against them.
     */
    public WarehouseConnectionDto disconnect(Long connectionId) {
        Long companyId = currentCompanyId();
        WarehouseConnection connection = requireParty(connectionId, companyId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new BadRequestException("error.partner.notActive");
        }
        connection.setStatus(ConnectionStatus.REVOKED);
        connection.setRevokedAt(Instant.now());
        WarehouseConnection saved = connectionRepository.save(connection);
        recordOnBothSides(saved, AuditAction.DELETE, "DISCONNECTED");
        return toDto(saved, companyId);
    }

    // ---------------------------------------------------------------------------------------------
    // Listing
    // ---------------------------------------------------------------------------------------------

    /**
     * Every connection the current company is a party to. One list serves both the warehouse account's
     * "companies we work for" page and the client's "warehouse partners" section; each row says which way
     * round it is being read.
     */
    public List<WarehouseConnectionDto> listForCurrentCompany() {
        Long companyId = currentCompanyId();
        List<WarehouseConnection> connections = new ArrayList<>(
                connectionRepository.findByWarehouseCompanyIdOrderByIdDesc(companyId));
        connections.addAll(connectionRepository.findByClientCompanyIdOrderByIdDesc(companyId));
        return toDtos(connections, companyId);
    }

    // ---------------------------------------------------------------------------------------------
    // DTO assembly
    // ---------------------------------------------------------------------------------------------

    private static ConnectCodeDto toDto(ConnectionCode code, Company company) {
        return new ConnectCodeDto(code.getCode(), company.getName(), code.getExpiresAt());
    }

    private WarehouseConnectionDto toDto(WarehouseConnection connection, Long viewerCompanyId) {
        return toDtos(List.of(connection), viewerCompanyId).get(0);
    }

    /**
     * Renders connections for one company. Company names come from a batched lookup, and the assigned
     * warehouse names from a batched cross-tenant one - they live in the client's tenant, which the
     * partner cannot reach with an ordinary tenant-scoped query.
     */
    private List<WarehouseConnectionDto> toDtos(List<WarehouseConnection> connections, Long viewerCompanyId) {
        if (connections.isEmpty()) {
            return List.of();
        }
        Set<Long> companyIds = new HashSet<>();
        Set<Long> warehouseIds = new HashSet<>();
        for (WarehouseConnection connection : connections) {
            companyIds.add(connection.getWarehouseCompanyId());
            companyIds.add(connection.getClientCompanyId());
            warehouseIds.addAll(connection.getWarehouseIds());
        }
        Map<Long, Company> companies = new HashMap<>();
        companyRepository.findAllById(companyIds).forEach(c -> companies.put(c.getId(), c));
        Map<Long, Warehouse> warehouses = new HashMap<>();
        if (!warehouseIds.isEmpty()) {
            warehouseRepository.findAllByIdInIgnoringTenant(warehouseIds)
                    .forEach(w -> warehouses.put(w.getId(), w));
        }

        List<WarehouseConnectionDto> result = new ArrayList<>(connections.size());
        for (WarehouseConnection connection : connections) {
            Company partner = companies.get(connection.getWarehouseCompanyId());
            Company client = companies.get(connection.getClientCompanyId());
            List<WarehouseConnectionDto.AssignedWarehouseDto> assigned = connection.getWarehouseIds().stream()
                    .map(warehouses::get)
                    .filter(java.util.Objects::nonNull)
                    .map(w -> new WarehouseConnectionDto.AssignedWarehouseDto(w.getId(), w.getName()))
                    .toList();

            result.add(new WarehouseConnectionDto(
                    connection.getId(),
                    connection.getStatus(),
                    connection.isCanSeePrices(),
                    connection.getWarehouseCompanyId(),
                    partner != null ? partner.getName() : null,
                    connection.getClientCompanyId(),
                    client != null ? client.getName() : null,
                    assigned,
                    viewerCompanyId.equals(connection.getWarehouseCompanyId()),
                    connection.getCreatedAt(),
                    connection.getRevokedAt()));
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private WarehouseConnection requireParty(Long connectionId, Long companyId) {
        WarehouseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found with id: " + connectionId));
        if (!companyId.equals(connection.getWarehouseCompanyId())
                && !companyId.equals(connection.getClientCompanyId())) {
            // Not our connection: report it as missing rather than confirming it exists.
            throw new ResourceNotFoundException("Connection not found with id: " + connectionId);
        }
        return connection;
    }

    private static void requireClientSide(WarehouseConnection connection, Long companyId) {
        if (!companyId.equals(connection.getClientCompanyId())) {
            throw new ForbiddenException("error.partner.clientOnly");
        }
    }

    /** The current company, refusing the operation unless it is an ordinary business. */
    private Company requireBusinessCompany() {
        Company company = requireCurrentCompany();
        if (company.isWarehouseAccount()) {
            throw new ForbiddenException("error.partner.businessOnly");
        }
        return company;
    }

    /** The current company, refusing the operation unless it is a warehouse account. */
    private Company requireWarehouseCompany() {
        Company company = requireCurrentCompany();
        if (!company.isWarehouseAccount()) {
            throw new ForbiddenException("error.partner.warehouseOnly");
        }
        return company;
    }

    private Company requireCurrentCompany() {
        Long companyId = currentCompanyId();
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));
    }

    /**
     * The company being acted for. These endpoints are administrator-only and a partner session is capped
     * at warehouse access, so in practice this is always the caller's own company.
     */
    private static Long currentCompanyId() {
        return SecurityUtil.currentCompanyId();
    }

    /**
     * Writes the change to both companies' audit trails. Access to a company's data is exactly the kind of
     * event each side should be able to see in their own log, and the log is {@code @TenantId}-scoped, so
     * the row genuinely has to be written twice.
     */
    private void recordOnBothSides(WarehouseConnection connection, AuditAction action, String detail) {
        Set<Long> sides = new LinkedHashSet<>();
        sides.add(connection.getWarehouseCompanyId());
        sides.add(connection.getClientCompanyId());
        for (Long companyId : sides) {
            TenantContext.callAs(companyId, () -> {
                auditService.record(AuditService.ENTITY_WAREHOUSE_PARTNER, connection.getId(), action, detail);
                return null;
            });
        }
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    /** Draws a readable code that nothing else is using yet. */
    private String allocateCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String candidate = "CO-" + randomBlock(4) + "-" + randomBlock(4);
            if (!codeRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new BadRequestException("error.partner.codeGenerationFailed");
    }

    private static String randomBlock(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return builder.toString();
    }
}
