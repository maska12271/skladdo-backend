package com.example.tenderapp.service;

import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.repository.ManufacturerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ManufacturerService {

    private final ManufacturerRepository manufacturerRepository;

    public ManufacturerService(ManufacturerRepository manufacturerRepository) {
        this.manufacturerRepository = manufacturerRepository;
    }

    public Page<Manufacturer> findAll(Pageable pageable) {
        return manufacturerRepository.findAll(pageable);
    }

    public Manufacturer findById(Long id) {
        return manufacturerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturer not found with id: " + id));
    }

    public Manufacturer save(Manufacturer manufacturer) {
        return manufacturerRepository.save(manufacturer);
    }

    public Manufacturer update(Long id, Manufacturer updatedManufacturer) {
        Manufacturer manufacturer = findById(id);
        manufacturer.setName(updatedManufacturer.getName());
        manufacturer.setCountry(updatedManufacturer.getCountry());
        manufacturer.setAddress(updatedManufacturer.getAddress());
        manufacturer.setEmail(updatedManufacturer.getEmail());
        manufacturer.setPhone(updatedManufacturer.getPhone());
        manufacturer.setWebsite(updatedManufacturer.getWebsite());
        manufacturer.setNotes(updatedManufacturer.getNotes());
        manufacturer.setActive(updatedManufacturer.getActive());
        return manufacturerRepository.save(manufacturer);
    }

    public void delete(Long id) {
        manufacturerRepository.delete(findById(id));
    }
}