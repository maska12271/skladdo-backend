package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderParticipantRequestDto {

    @NotBlank
    private String manufacturerName;

    // Nullable: a participant can be marked as taking part before a price is known.
    private Double offeredPrice;

    private String notes;
    private Boolean winner = false;
    private Boolean participating = false;
}