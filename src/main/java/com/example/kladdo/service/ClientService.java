package com.example.kladdo.service;

import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Client;
import com.example.kladdo.repository.ClientRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public Page<Client> findAll(boolean includeArchived, Pageable pageable) {
        return includeArchived
                ? clientRepository.findAll(pageable)
                : clientRepository.findNotArchived(pageable);
    }

    public Client findById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
    }

    public Client save(Client client) {
        // A blank registration code must be stored as NULL, not "" — the column is unique, and several
        // clients legitimately have no code (multiple NULLs are allowed, multiple ""s are not).
        client.setRegistrationCode(blankToNull(client.getRegistrationCode()));
        return clientRepository.save(client);
    }

    public Client update(Long id, Client updatedClient) {
        Client client = findById(id);
        client.setName(updatedClient.getName());
        client.setRegistrationCode(blankToNull(updatedClient.getRegistrationCode()));
        client.setEmail(updatedClient.getEmail());
        client.setPhone(updatedClient.getPhone());
        client.setAddress(updatedClient.getAddress());
        client.setContactPerson(updatedClient.getContactPerson());
        client.setNotes(updatedClient.getNotes());
        client.setActive(updatedClient.getActive());
        return clientRepository.save(client);
    }

    public void delete(Long id) {
        clientRepository.delete(findById(id));
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
