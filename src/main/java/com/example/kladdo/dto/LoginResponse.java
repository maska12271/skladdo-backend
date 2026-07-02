package com.example.kladdo.dto;

public record LoginResponse(
        String token,
        UserDto user
) {
}
