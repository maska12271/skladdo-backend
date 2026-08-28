package com.example.skladdo.service;

import com.example.skladdo.dto.PartnerContactDto;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.PartnerContact;
import com.example.skladdo.repository.PartnerContactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The named people at a client or a manufacturer.
 *
 * <p>Deliberately a small CRUD of its own rather than a field on the partner form. Adding a colleague at
 * a supplier, correcting an address or removing someone who has left should not mean opening and re-saving
 * the whole partner record - that is a bigger, riskier edit than the change deserves, and it is the reason
 * the single {@code contactPerson} string it replaces was so often stale.</p>
 *
 * <p>Every method goes through the owning partner first, which is what scopes it: the partner lookup is
 * tenant-filtered, so a contact id from another company resolves to nothing rather than to someone else's
 * data.</p>
 */
@Service
public class PartnerContactService {

    private final PartnerContactRepository contactRepository;
    private final ClientService clientService;
    private final ManufacturerService manufacturerService;

    public PartnerContactService(PartnerContactRepository contactRepository,
                                 ClientService clientService,
                                 ManufacturerService manufacturerService) {
        this.contactRepository = contactRepository;
        this.clientService = clientService;
        this.manufacturerService = manufacturerService;
    }

    @Transactional(readOnly = true)
    public List<PartnerContactDto> listForClient(Long clientId) {
        clientService.findById(clientId); // 404s for another company's client
        return contactRepository.findByClientIdOrderByNameAsc(clientId).stream()
                .map(PartnerContactDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PartnerContactDto> listForManufacturer(Long manufacturerId) {
        manufacturerService.findById(manufacturerId);
        return contactRepository.findByManufacturerIdOrderByNameAsc(manufacturerId).stream()
                .map(PartnerContactDto::from)
                .toList();
    }

    @Transactional
    public PartnerContactDto createForClient(Long clientId, PartnerContactDto request) {
        clientService.findById(clientId);
        PartnerContact contact = new PartnerContact();
        contact.setClientId(clientId);
        return save(contact, request);
    }

    @Transactional
    public PartnerContactDto createForManufacturer(Long manufacturerId, PartnerContactDto request) {
        manufacturerService.findById(manufacturerId);
        PartnerContact contact = new PartnerContact();
        contact.setManufacturerId(manufacturerId);
        return save(contact, request);
    }

    @Transactional
    public PartnerContactDto updateForClient(Long clientId, Long contactId, PartnerContactDto request) {
        return save(requireOfClient(clientId, contactId), request);
    }

    @Transactional
    public PartnerContactDto updateForManufacturer(Long manufacturerId, Long contactId, PartnerContactDto request) {
        return save(requireOfManufacturer(manufacturerId, contactId), request);
    }

    @Transactional
    public void deleteForClient(Long clientId, Long contactId) {
        contactRepository.delete(requireOfClient(clientId, contactId));
    }

    @Transactional
    public void deleteForManufacturer(Long manufacturerId, Long contactId) {
        contactRepository.delete(requireOfManufacturer(manufacturerId, contactId));
    }

    /**
     * One manufacturer contact, or empty when the id names nobody at that manufacturer.
     *
     * <p>Used by the send path, which has to be sure the address it is about to write to belongs to the
     * manufacturer being emailed rather than to whoever the request named.</p>
     */
    @Transactional(readOnly = true)
    public PartnerContact requireOfManufacturer(Long manufacturerId, Long contactId) {
        return contactRepository.findByIdAndManufacturerId(contactId, manufacturerId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found with id: " + contactId));
    }

    private PartnerContact requireOfClient(Long clientId, Long contactId) {
        return contactRepository.findByIdAndClientId(contactId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found with id: " + contactId));
    }

    private PartnerContactDto save(PartnerContact contact, PartnerContactDto request) {
        contact.setName(request.name().trim());
        contact.setPosition(blankToNull(request.position()));
        contact.setEmail(blankToNull(request.email()));
        return PartnerContactDto.from(contactRepository.save(contact));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
