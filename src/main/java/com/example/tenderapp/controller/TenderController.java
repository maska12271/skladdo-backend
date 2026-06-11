package com.example.tenderapp.controller;

import com.example.tenderapp.dto.TenderRequestDto;
import com.example.tenderapp.dto.TenderResponseDto;
import com.example.tenderapp.service.TenderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenders")
@RequiredArgsConstructor
public class TenderController {

    private final TenderService tenderService;

    @GetMapping
    public Page<TenderResponseDto> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        return tenderService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public TenderResponseDto findById(@PathVariable Long id) {
        return tenderService.findById(id);
    }

    @PostMapping
    public TenderResponseDto create(@Valid @RequestBody TenderRequestDto dto) {
        return tenderService.create(dto);
    }

    @PutMapping("/{id}")
    public TenderResponseDto update(@PathVariable Long id, @Valid @RequestBody TenderRequestDto dto) {
        return tenderService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        tenderService.delete(id);
    }
}