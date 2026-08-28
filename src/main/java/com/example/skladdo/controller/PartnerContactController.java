package com.example.skladdo.controller;

import com.example.skladdo.dto.PartnerContactDto;
import com.example.skladdo.service.PartnerContactService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The people at a client or a manufacturer, as sub-resources of the partner they work for.
 *
 * <p>Nested under the partner rather than sitting at {@code /api/contacts}: which partner a contact
 * belongs to is then a fact about the URL rather than a field in the body, so it cannot be re-pointed by
 * a request and every operation is scoped by a lookup that is already tenant-filtered.</p>
 *
 * <p>Access follows the partner: reading a client's contacts needs the same rights as reading the client.
 * Reading is on {@code canReadReference} so someone building an order - who is allowed to pick a client
 * without holding the clients module - can also see who to address it to.</p>
 */
@RestController
@Tag(name = "Partner contacts")
public class PartnerContactController {

    private final PartnerContactService contactService;

    public PartnerContactController(PartnerContactService contactService) {
        this.contactService = contactService;
    }

    // --- Clients ----------------------------------------------------------------------------------

    @GetMapping("/api/clients/{clientId}/contacts")
    @PreAuthorize("@perm.canReadReference(authentication, 'CLIENTS')")
    public List<PartnerContactDto> listForClient(@PathVariable Long clientId) {
        return contactService.listForClient(clientId);
    }

    @PostMapping("/api/clients/{clientId}/contacts")
    @PreAuthorize("@perm.canEdit(authentication, 'CLIENTS')")
    public PartnerContactDto createForClient(@PathVariable Long clientId,
                                             @Valid @RequestBody PartnerContactDto request) {
        return contactService.createForClient(clientId, request);
    }

    @PutMapping("/api/clients/{clientId}/contacts/{contactId}")
    @PreAuthorize("@perm.canEdit(authentication, 'CLIENTS')")
    public PartnerContactDto updateForClient(@PathVariable Long clientId,
                                             @PathVariable Long contactId,
                                             @Valid @RequestBody PartnerContactDto request) {
        return contactService.updateForClient(clientId, contactId, request);
    }

    @DeleteMapping("/api/clients/{clientId}/contacts/{contactId}")
    @PreAuthorize("@perm.canEdit(authentication, 'CLIENTS')")
    public void deleteForClient(@PathVariable Long clientId, @PathVariable Long contactId) {
        contactService.deleteForClient(clientId, contactId);
    }

    // --- Manufacturers ----------------------------------------------------------------------------

    @GetMapping("/api/manufacturers/{manufacturerId}/contacts")
    @PreAuthorize("@perm.canReadReference(authentication, 'MANUFACTURERS')")
    public List<PartnerContactDto> listForManufacturer(@PathVariable Long manufacturerId) {
        return contactService.listForManufacturer(manufacturerId);
    }

    @PostMapping("/api/manufacturers/{manufacturerId}/contacts")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURERS')")
    public PartnerContactDto createForManufacturer(@PathVariable Long manufacturerId,
                                                   @Valid @RequestBody PartnerContactDto request) {
        return contactService.createForManufacturer(manufacturerId, request);
    }

    @PutMapping("/api/manufacturers/{manufacturerId}/contacts/{contactId}")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURERS')")
    public PartnerContactDto updateForManufacturer(@PathVariable Long manufacturerId,
                                                    @PathVariable Long contactId,
                                                    @Valid @RequestBody PartnerContactDto request) {
        return contactService.updateForManufacturer(manufacturerId, contactId, request);
    }

    @DeleteMapping("/api/manufacturers/{manufacturerId}/contacts/{contactId}")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURERS')")
    public void deleteForManufacturer(@PathVariable Long manufacturerId, @PathVariable Long contactId) {
        contactService.deleteForManufacturer(manufacturerId, contactId);
    }
}
