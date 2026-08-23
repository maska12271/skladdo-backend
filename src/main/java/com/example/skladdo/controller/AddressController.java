package com.example.skladdo.controller;

import com.example.skladdo.dto.AddressSuggestionDto;
import com.example.skladdo.service.AddressLookupService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Address typeahead used by every address field in the app. Returns provider-agnostic suggestions for
 * a partial query; available to any authenticated user.
 */
@RestController
@RequestMapping("/api/address")
@Tag(name = "Address lookup")
public class AddressController {

    private final AddressLookupService addressLookupService;

    public AddressController(AddressLookupService addressLookupService) {
        this.addressLookupService = addressLookupService;
    }

    @GetMapping("/suggest")
    @PreAuthorize("isAuthenticated()")
    public List<AddressSuggestionDto> suggest(@RequestParam("q") String query) {
        return addressLookupService.suggest(query);
    }
}
