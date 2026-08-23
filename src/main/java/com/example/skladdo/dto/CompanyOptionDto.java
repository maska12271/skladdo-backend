package com.example.skladdo.dto;

/**
 * One entry in the "which company am I working in" switcher: the account's own company plus every client
 * company it can reach through an active warehouse connection.
 */
public record CompanyOptionDto(
        Long id,
        String name,
        /** True for the company that owns the login (always present, always first). */
        boolean home,
        /** True for the company the current session is working in. */
        boolean current
) {
}
