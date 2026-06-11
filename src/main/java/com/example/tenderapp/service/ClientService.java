package com.example.tenderapp.service;

import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Client;
import com.example.tenderapp.repository.ClientRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public Page<Client> findAll(Pageable pageable) {
        return clientRepository.findAll(pageable);
    }

    public Client findById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
    }

    public Client save(Client client) {
        return clientRepository.save(client);
    }

    public Client update(Long id, Client updatedClient) {
        Client client = findById(id);
        client.setName(updatedClient.getName());
        client.setRegistrationCode(updatedClient.getRegistrationCode());
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
}