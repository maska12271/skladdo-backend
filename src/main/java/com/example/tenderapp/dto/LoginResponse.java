package com.example.tenderapp.dto;

public record LoginResponse(
        String token,
        UserDto user
) {
}
