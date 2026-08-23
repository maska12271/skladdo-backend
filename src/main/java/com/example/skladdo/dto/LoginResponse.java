package com.example.skladdo.dto;

public record LoginResponse(
        String token,
        UserDto user
) {
}
