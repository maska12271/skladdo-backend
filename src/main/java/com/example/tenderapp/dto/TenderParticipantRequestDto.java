package com.example.tenderapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderParticipantRequestDto {

    @NotBlank
    private String manufacturerName;

    @NotNull
    private Double offeredPrice;

    private String notes;
    private Boolean winner = false;
}