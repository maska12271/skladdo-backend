package com.example.kladdo.dto;

import jakarta.validation.constraints.NotNull;

/** Asks for a session token pinned to another company the caller may work in. */
public record SwitchCompanyRequest(
        @NotNull Long companyId
) {
}
