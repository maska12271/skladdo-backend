package com.example.kladdo.controller;

import com.example.kladdo.dto.ConnectCodeDto;
import com.example.kladdo.dto.RedeemConnectionCodeRequest;
import com.example.kladdo.dto.UpdateConnectionRequest;
import com.example.kladdo.dto.UpdateConnectionWarehousesRequest;
import com.example.kladdo.dto.WarehouseConnectionDto;
import com.example.kladdo.service.WarehousePartnerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Connections between a warehouse account and the companies it works for.
 *
 * <p>Restricted to owners and administrators throughout: a connection decides whether an outside company
 * can read and change this company's data, which is an ownership decision, not a day-to-day one. It also
 * means a partner's staff cannot manage connections for a client company they have switched into - a
 * partner session is capped at warehouse access and never carries a manager role.</p>
 */
@RestController
@RequestMapping("/api/warehouse-partners")
@Tag(name = "Warehouse partners")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class WarehousePartnerController {

    private final WarehousePartnerService partnerService;

    public WarehousePartnerController(WarehousePartnerService partnerService) {
        this.partnerService = partnerService;
    }

    /** Every connection this company is a party to, on either side. */
    @GetMapping
    public List<WarehouseConnectionDto> list() {
        return partnerService.listForCurrentCompany();
    }

    // --- The code (business companies) -----------------------------------------------------------

    /** Our current invitation code, issuing one if the last has expired or been used. */
    @GetMapping("/code")
    public ConnectCodeDto code() {
        return partnerService.currentCode();
    }

    /** Draws a fresh code, which immediately abandons the previous one. */
    @PostMapping("/code/regenerate")
    public ConnectCodeDto regenerateCode() {
        return partnerService.issueCode();
    }

    // --- Redeeming (warehouse accounts) ----------------------------------------------------------

    /** We are a warehouse account joining the company whose code we were given. */
    @PostMapping("/redeem")
    public WarehouseConnectionDto redeem(@Valid @RequestBody RedeemConnectionCodeRequest request) {
        return partnerService.redeem(request);
    }

    // --- Client-side controls --------------------------------------------------------------------

    /** Client-side only: which of our warehouses this partner may work in. */
    @PutMapping("/{id}/warehouses")
    public WarehouseConnectionDto setWarehouses(@PathVariable Long id,
                                                @RequestBody UpdateConnectionWarehousesRequest request) {
        return partnerService.setWarehouses(id, request == null ? null : request.warehouseIds());
    }

    /** Client-side only: whether the partner's staff may see our monetary values. */
    @PutMapping("/{id}")
    public WarehouseConnectionDto update(@PathVariable Long id,
                                         @Valid @RequestBody UpdateConnectionRequest request) {
        return partnerService.setCanSeePrices(id, request.canSeePrices());
    }

    /** Ends the connection. Either side may do this. */
    @PostMapping("/{id}/disconnect")
    public WarehouseConnectionDto disconnect(@PathVariable Long id) {
        return partnerService.disconnect(id);
    }
}
