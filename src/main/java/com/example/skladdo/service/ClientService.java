package com.example.skladdo.service;

import com.example.skladdo.model.AuditAction;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Client;
import com.example.skladdo.model.PartnerContact;
import com.example.skladdo.repository.ClientRepository;
import com.example.skladdo.repository.PartnerContactRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    private final PartnerContactRepository contactRepository;

    private final AuditService auditService;

    // The contact repository rather than PartnerContactService: that service already depends on this one
    // (it scopes every call through the client), and taking it back would be a constructor cycle.
    public ClientService(ClientRepository clientRepository,
                         PartnerContactRepository contactRepository,
                         AuditService auditService) {
        this.clientRepository = clientRepository;
        this.contactRepository = contactRepository;
        this.auditService = auditService;
    }

    /**
     * Paged client search. Free-text matches name / registration code / email / any contact person's name; the
     * status filter narrows to active vs archived (a {@code null} archived flag counts as active).
     * {@code includeArchived} is the base visibility for reference/dropdown callers — when false,
     * archived clients are hidden regardless of the status filter.
     */
    public Page<Client> findAll(String search, List<String> status, boolean includeArchived, Pageable pageable) {
        Specification<Client> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                // Contacts are a separate table with no mapped association back to the client (see
                // PartnerContact), so matching a person's name takes an EXISTS subquery rather than a
                // join. Kept because searching a client by who you deal with there is how people
                // actually find them - it worked when the name was a column on CLIENT, and losing it
                // to the move would be a downgrade nobody asked for.
                Subquery<Long> byContact = query.subquery(Long.class);
                Root<PartnerContact> contact = byContact.from(PartnerContact.class);
                byContact.select(contact.get("id")).where(
                        cb.equal(contact.get("clientId"), root.get("id")),
                        cb.like(cb.lower(contact.get("name")), like));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("registrationCode")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.exists(byContact)
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
        client.setNotes(updatedClient.getNotes());
        client.setActive(updatedClient.getActive());
        Client saved = clientRepository.save(client);
        auditService.record(AuditService.ENTITY_CLIENT, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    // Transactional so the contacts and the client are removed together: taking the contacts out and
    // then failing on the client would leave a live record with its people silently gone.
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        Client client = findById(id);
        String name = client.getName();
        // Contacts hold the client id as a plain column, so nothing at the database level removes them -
        // left behind they would belong to nobody and be reachable from nowhere.
        contactRepository.deleteByClientId(id);
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
