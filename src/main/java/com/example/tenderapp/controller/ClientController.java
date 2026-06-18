package com.example.tenderapp.controller;

import com.example.tenderapp.dto.ClientDetailsDto;
import com.example.tenderapp.model.Client;
import com.example.tenderapp.service.ClientAnalyticsService;
import com.example.tenderapp.service.ClientService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/clients")
@Tag(name = "Clients")
public class ClientController {

    private final ClientService clientService;
    private final ClientAnalyticsService clientAnalyticsService;

    public ClientController(ClientService clientService,
                            ClientAnalyticsService clientAnalyticsService) {
        this.clientService = clientService;
        this.clientAnalyticsService = clientAnalyticsService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'CLIENTS')")
    public Page<Client> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return clientService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'CLIENTS')")
    public Client getById(@PathVariable Long id) {
        return clientService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canReadReference(authentication, 'CLIENTS')")
    public ClientDetailsDto getDetails(@PathVariable Long id) {
        return clientAnalyticsService.getDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'CLIENTS')")
    public Client create(@Valid @RequestBody Client client) {
        return clientService.save(client);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'CLIENTS')")
    public Client update(@PathVariable Long id, @Valid @RequestBody Client client) {
        return clientService.update(id, client);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'CLIENTS')")
    public void delete(@PathVariable Long id) {
        clientService.delete(id);
    }
}