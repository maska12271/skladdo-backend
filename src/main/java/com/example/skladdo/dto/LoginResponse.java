package com.example.skladdo.dto;

import com.example.skladdo.model.AddonType;

import java.util.Set;

public record LoginResponse(
        String token,
        UserDto user
) {

    /** The same response with the company's add-ons attached to the profile. See {@link UserDto#withAddons}. */
    public LoginResponse withAddons(Set<AddonType> addons) {
        return new LoginResponse(token, user.withAddons(addons));
    }
}
