package com.example.skladdo.dto;

import java.time.Instant;

/**
 * The invitation code a business company hands to a warehouse account, with the company it identifies and
 * the moment it stops working. The expiry is part of the payload because it is the thing the person
 * sharing the code has to act on.
 */
public record ConnectCodeDto(
        String code,
        String name,
        Instant expiresAt
) {
}
