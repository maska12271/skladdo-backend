package com.example.kladdo.service;

import com.example.kladdo.model.AuditAction;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Client;
import com.example.kladdo.repository.ClientRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    private final AuditService auditService;

    public ClientService(ClientRepository clientRepository, AuditService auditService) {
        this.clientRepository = clientRepository;
        this.auditService = auditService;
    }

    /**
     * Paged client search. Free-text matches name / registration code / email / contact person; the
     * status filter narrows to active vs archived (a {@code null} archived flag counts as active).
     * {@code includeArchived} is the base visibility for reference/dropdown callers — when false,
     * archived clients are hidden regardless of the status filter.
     */
    public Page<Client> findAll(String search, List<String> status, boolean includeArchived, Pageable pageable) {
        Specification<Client> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("registrationCode")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("contactPerson")), like)
                ));
            }

            Predicate notArchived = cb.or(cb.equal(root.get("archived"), false), cb.isNull(root.get("archived")));
            boolean wantActive = status != null && status.contains("active");
            boolean wantArchived = status != null && status.contains("archived");
            if (wantActive ^ wantArchived) { // exactly one selected → narrow to it
                predicates.add(wantArchived ? cb.equal(root.get("archived"), true) : notArchived);
            } else if (!includeArchived) {
                predicates.add(notArchived);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return clientRepository.findAll(specification, pageable);
    }

    public Client findById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
    }

    public Client save(Client client) {
        // A blank registration code must be stored as NULL, not "" — the column is unique, and several
        // clients legitimately have no code (multiple NULLs are allowed, multiple ""s are not).
        client.setRegistrationCode(blankToNull(client.getRegistrationCode()));
        boolean isNew = client.getId() == null;
        Client saved = clientRepository.save(client);
        // Only a genuinely new record is a CREATE; this method is also the plain re-save path.
        auditService.record(AuditService.ENTITY_CLIENT, saved.getId(),
                isNew ? AuditAction.CREATE : AuditAction.UPDATE, saved.getName());
        return saved;
    }

    public Client update(Long id, Client updatedClient) {
        Client client = findById(id);
        client.setName(updatedClient.getName());
        client.setRegistrationCode(blankToNull(updatedClient.getRegistrationCode()));
        client.setEmail(updatedClient.getEmail());
        client.setPhone(updatedClient.getPhone());
        client.setCountry(updatedClient.getCountry());
        client.setAddress(updatedClient.getAddress());
        client.setContactPerson(updatedClient.getContactPerson());
        client.setNotes(updatedClient.getNotes());
        client.setActive(updatedClient.getActive());
        Client saved = clientRepository.save(client);
        auditService.record(AuditService.ENTITY_CLIENT, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    public void delete(Long id) {
        Client client = findById(id);
        String name = client.getName();
        clientRepository.delete(client);
        auditService.record(AuditService.ENTITY_CLIENT, id, AuditAction.DELETE, name);
    }

    /** Countries used by this tenant's clients, most-used first (drives the country picker order). */
    public List<String> getCountriesByUsage() {
        return clientRepository.findCountriesByUsage();
    }

    /** Archives or restores a client. Archived clients are hidden from the default list and pickers. */
    public Client setArchived(Long id, boolean archived) {
        Client client = findById(id);
        client.setArchived(archived);
        return clientRepository.save(client);
    }

    /** Trims a string and returns null when it is null or blank (so unique columns store NULL, not ""). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
